package com.hdz.wearcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.EnvironmentalReverb;
import android.media.audiofx.Virtualizer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Layer 1 本机单端空间声场 DSP 引擎 + Layer 3 防叠加仲裁器。
 *
 * <p>诚实物理边界: 仅作用于本机播放的媒体音频（手表独立播歌或手机端播歌）。
 * 优先挂载外部播放器广播的音频会话 (sessionId &gt; 0)，支持插入式混响与可选虚拟化；
 * 未收到会话广播且开启全局兼容模式时回退至 sessionId = 0（绝不在 sessionId = 0 挂 EnvironmentalReverb）。
 * 使用 AudioPlaybackCallback 按需懒挂载并在静音 45 秒后自动释放，避免后台常驻耗电。
 */
final class WearEarsSpatialEffect {
    private static final String TAG = "WearEarsSpatial";

    static final int SCENE_WIDE = 0;
    static final int SCENE_CINEMA = 1;
    static final int SCENE_CONCERT = 2;
    static final int SCENE_STUDIO = 3;

    private static final long IDLE_RELEASE_MS = 45_000L;
    private static final float PREFERRED_FRAME_MS = 10.0f;
    private static final float INPUT_HEADROOM_DB = -3.0f;
    private static final float LIMITER_THRESHOLD_DB = -1.5f;
    private static final float LIMITER_RATIO = 10.0f;
    private static final float LIMITER_ATTACK_MS = 1.0f;
    private static final float LIMITER_RELEASE_MS = 60.0f;

    /** 10 段连续 Pre-EQ 截止频率 (Hz) */
    private static final float[] PRE_EQ_CUTOFFS = {
            60f, 120f, 250f, 500f, 1000f, 2200f, 4000f, 6500f, 9500f, 16000f
    };

    /** 4 种空间声场预设的 10 段 Pre-EQ 增益 (dB) */
    private static final float[][] SCENE_EQ_GAINS = {
            // 0: Natural Wide (自然宽阔) - Pinna 外化陷波 6.5k-9.5k + 空气感 16k
            {0.0f, 1.5f, 0.0f, -1.0f, 0.0f, 1.5f, 0.0f, -1.5f, -1.5f, 2.0f},
            // 1: Cinema (影院) - 低频下潜 + 对白清晰度
            {3.0f, 2.0f, 0.0f, 0.0f, 0.0f, 1.5f, 0.0f, -1.0f, 0.0f, 0.0f},
            // 2: Concert Hall (音乐厅) - 温暖低中频 + 厅堂高频延伸
            {0.0f, 2.0f, 0.0f, -0.8f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 2.5f},
            // 3: Vocal Studio (人声棚) - 干净低切 + 2.2k-4k 人声结像
            {-1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.0f, 2.0f, 0.0f, 0.0f, 1.0f}
    };

    /** 3 段多段压缩截止频率 (Hz) 与温和动态参数 */
    private static final float[] MBC_CUTOFFS = {250f, 3500f, 20000f};
    private static final float[] MBC_RATIOS = {1.8f, 1.6f, 2.0f};
    private static final float[] MBC_THRESHOLDS = {-18.0f, -16.0f, -18.0f};
    private static final float MBC_ATTACK_MS = 20.0f;
    private static final float MBC_RELEASE_MS = 150.0f;
    private static final float MBC_KNEE_DB = 6.0f;

    /** Virtualizer 强度上限 150-400/1000（避免高强度相位发空） */
    private static final short[] SCENE_VIRTUALIZER_STRENGTH = {250, 400, 350, 150};

    enum Status {
        IDLE(R.string.local_spatial_status_idle),
        ACTIVE_SESSION(R.string.local_spatial_status_session),
        ACTIVE_GLOBAL(R.string.local_spatial_status_global),
        UNSUPPORTED(R.string.local_spatial_status_unsupported),
        BYPASSED_HW(R.string.local_spatial_status_bypass_hw),
        BYPASSED_OS(R.string.local_spatial_status_bypass_os);

        final int stringRes;

        Status(int stringRes) {
            this.stringRes = stringRes;
        }
    }

    interface StatusListener {
        void onSpatialStatusChanged(Status status);
    }

    private static volatile WearEarsSpatialEffect instance;

    private final Context app;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<StatusListener> listeners = new CopyOnWriteArraySet<>();
    private final LinkedHashMap<Integer, String> openSessions = new LinkedHashMap<>();

    private boolean monitorsRegistered;
    private boolean lastEarbudHwSpatialOn;
    private boolean labActive;
    private boolean playbackActive;
    private boolean userTriggeredAttach;
    private boolean idleTimerScheduled;
    private boolean insertReverbSupported;
    private boolean virtualizerSupported;
    private boolean effectsProbed;

    private int attachedSessionId = -1;
    private int attachedScene = -1;
    private Status currentStatus = Status.IDLE;

    private DynamicsProcessing dynamics;
    private EnvironmentalReverb reverb;
    private Virtualizer virtualizer;

    private final Runnable idleReleaseTask = new Runnable() {
        @Override
        public void run() {
            onIdleTimeout();
        }
    };

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
            String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            if (sessionId <= 0) return;
            if (pkg != null && pkg.equals(app.getPackageName())) {
                return;
            }
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
                onExternalSessionOpened(sessionId, pkg);
            } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
                onExternalSessionClosed(sessionId);
            }
        }
    };

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    onPlaybackChanged(configs);
                }
            };

    private WearEarsSpatialEffect(Context appContext) {
        this.app = appContext;
    }

    static WearEarsSpatialEffect get(Context ctx) {
        WearEarsSpatialEffect current = instance;
        if (current == null) {
            synchronized (WearEarsSpatialEffect.class) {
                current = instance;
                if (current == null) {
                    Context appCtx = ctx.getApplicationContext();
                    current = new WearEarsSpatialEffect(appCtx != null ? appCtx : ctx);
                    instance = current;
                }
            }
        }
        return current;
    }

    synchronized Status status() {
        return currentStatus;
    }

    synchronized int statusStringRes() {
        return currentStatus.stringRes;
    }

    void addListener(StatusListener listener) {
        if (listener != null) listeners.add(listener);
    }

    void removeListener(StatusListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** 用户主动拨动开关或切换声场时调用：给予一次即刻探测/挂载机会以立即反馈 ROM 支持状态。 */
    synchronized void syncFromUserAction(Context ctx, boolean earbudHwSpatialOn) {
        userTriggeredAttach = true;
        sync(ctx, earbudHwSpatialOn);
    }

    /** 360° 双耳 HRTF 实验室试听期间临时旁路本机 Layer 1 DSP，防止双重空间化叠加。 */
    synchronized void setLabActive(Context ctx, boolean active) {
        if (labActive == active) return;
        labActive = active;
        sync(ctx, lastEarbudHwSpatialOn);
    }

    /**
     * 同步 Layer 3 防叠加仲裁与 Layer 1 本机 DSP 挂载状态（幂等，无多余重建）。
     */
    synchronized void sync(Context ctx, boolean earbudHwSpatialOn) {
        Context c = ctx != null ? ctx : app;
        lastEarbudHwSpatialOn = earbudHwSpatialOn;

        if (!AppPrefs.localSpatialEnabled(c) || labActive) {
            userTriggeredAttach = false;
            cancelIdleTimer();
            unregisterMonitors();
            releaseEffectsLocked();
            setStatusLocked(Status.IDLE);
            return;
        }

        if (earbudHwSpatialOn) {
            userTriggeredAttach = false;
            cancelIdleTimer();
            unregisterMonitors();
            releaseEffectsLocked();
            setStatusLocked(Status.BYPASSED_HW);
            return;
        }

        if (SystemSpatializer.isOsSpatializerActive(c)) {
            userTriggeredAttach = false;
            cancelIdleTimer();
            unregisterMonitors();
            releaseEffectsLocked();
            setStatusLocked(Status.BYPASSED_OS);
            return;
        }

        registerMonitors();
        refreshPlaybackStateLocked();

        int scene = AppPrefs.localSpatialScene(c);
        int activeSessionId = latestExternalSessionIdLocked();
        if (activeSessionId > 0) {
            if (playbackActive) {
                cancelIdleTimer();
                userTriggeredAttach = false;
                attachOrUpdateLocked(activeSessionId, scene, false);
                if (currentStatus == Status.ACTIVE_SESSION) return;
                // 僵尸或已关闭会话挂载失败：剔除失效 sessionId，允许继续尝试全局回退
                openSessions.remove(activeSessionId);
            } else if (userTriggeredAttach) {
                userTriggeredAttach = false;
                attachOrUpdateLocked(activeSessionId, scene, false);
                if (currentStatus == Status.ACTIVE_SESSION) {
                    scheduleIdleTimerIfNeeded();
                    return;
                }
                openSessions.remove(activeSessionId);
            } else if (dynamics != null && attachedSessionId == activeSessionId) {
                attachOrUpdateLocked(activeSessionId, scene, false);
                scheduleIdleTimerIfNeeded();
                return;
            } else {
                cancelIdleTimer();
                releaseEffectsLocked();
                if (currentStatus != Status.UNSUPPORTED) {
                    setStatusLocked(Status.IDLE);
                }
                return;
            }
        }

        if (!AppPrefs.localSpatialGlobalFallback(c)) {
            cancelIdleTimer();
            releaseEffectsLocked();
            setStatusLocked(Status.IDLE);
            return;
        }

        // 无外部会话广播且允许全局兼容模式:
        // 仅在蓝牙媒体正在播放、已有挂载，或用户刚刚主动触发时挂载，避免 24/7 空转耗电。
        if (playbackActive) {
            cancelIdleTimer();
            userTriggeredAttach = false;
            attachOrUpdateLocked(0, scene, true);
        } else if (userTriggeredAttach) {
            userTriggeredAttach = false;
            attachOrUpdateLocked(0, scene, true);
            if (currentStatus == Status.ACTIVE_GLOBAL) {
                scheduleIdleTimer();
            }
        } else if (dynamics != null && attachedSessionId == 0) {
            attachOrUpdateLocked(0, scene, true);
            scheduleIdleTimerIfNeeded();
        } else {
            cancelIdleTimer();
            releaseEffectsLocked();
            if (currentStatus != Status.UNSUPPORTED) {
                setStatusLocked(Status.IDLE);
            }
        }
    }

    synchronized void release() {
        userTriggeredAttach = false;
        cancelIdleTimer();
        unregisterMonitors();
        openSessions.clear();
        releaseEffectsLocked();
        setStatusLocked(Status.IDLE);
    }

    private void onExternalSessionOpened(final int sessionId, final String pkg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (WearEarsSpatialEffect.this) {
                    openSessions.remove(sessionId);
                    while (openSessions.size() >= 16) {
                        Integer oldest = openSessions.keySet().iterator().next();
                        openSessions.remove(oldest);
                    }
                    openSessions.put(sessionId, pkg == null ? "" : pkg);
                    userTriggeredAttach = true;
                    sync(app, lastEarbudHwSpatialOn);
                }
            }
        });
    }

    private void onExternalSessionClosed(final int sessionId) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (WearEarsSpatialEffect.this) {
                    openSessions.remove(sessionId);
                    if (attachedSessionId == sessionId) {
                        releaseEffectsLocked();
                    }
                    sync(app, lastEarbudHwSpatialOn);
                }
            }
        });
    }

    private void onPlaybackChanged(final List<AudioPlaybackConfiguration> configs) {
        final boolean btMedia = isBluetoothMediaPlaying(audioManager(), configs);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (WearEarsSpatialEffect.this) {
                    playbackActive = btMedia;
                    if (!AppPrefs.localSpatialEnabled(app)) {
                        return;
                    }
                    if (btMedia) {
                        cancelIdleTimer();
                        sync(app, lastEarbudHwSpatialOn);
                    } else if (dynamics != null || reverb != null || virtualizer != null) {
                        scheduleIdleTimer();
                    }
                }
            }
        });
    }

    private synchronized void onIdleTimeout() {
        idleTimerScheduled = false;
        refreshPlaybackStateLocked();
        if (playbackActive) {
            return;
        }
        if (dynamics != null || reverb != null || virtualizer != null) {
            releaseEffectsLocked();
            if (currentStatus == Status.ACTIVE_GLOBAL || currentStatus == Status.ACTIVE_SESSION) {
                setStatusLocked(Status.IDLE);
            }
        }
    }

    private void attachOrUpdateLocked(int sessionId, int scene, boolean globalFallback) {
        probeEffectsOnceLocked();
        int clampedScene = clampScene(scene);

        if (dynamics != null && attachedSessionId == sessionId) {
            boolean stillControlled = false;
            try {
                stillControlled = dynamics.hasControl();
            } catch (RuntimeException ignored) {
                stillControlled = false;
            }
            if (stillControlled) {
                if (attachedScene != clampedScene) {
                    applySceneInPlaceLocked(sessionId, clampedScene);
                }
                setStatusLocked(globalFallback ? Status.ACTIVE_GLOBAL : Status.ACTIVE_SESSION);
                return;
            }
            releaseEffectsLocked();
        } else if (dynamics != null) {
            releaseEffectsLocked();
        }

        DynamicsProcessing dp = null;
        try {
            DynamicsProcessing.Config config = buildConfig(clampedScene);
            dp = new DynamicsProcessing(0, sessionId, config);
            if (!dp.hasControl()) {
                safeRelease(dp);
                setStatusLocked(Status.UNSUPPORTED);
                return;
            }
            int enableRes = dp.setEnabled(true);
            if (enableRes != AudioEffect.SUCCESS && !dp.getEnabled()) {
                safeRelease(dp);
                setStatusLocked(Status.UNSUPPORTED);
                return;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "DynamicsProcessing attach failed on session " + sessionId, e);
            safeRelease(dp);
            setStatusLocked(Status.UNSUPPORTED);
            return;
        }

        dynamics = dp;
        attachedSessionId = sessionId;
        attachedScene = clampedScene;

        // 仅在 sessionId > 0 且设备暴露 EFFECT_INSERT 描述符时挂载 EnvironmentalReverb。
        // 绝不在 sessionId == 0 实例化 EnvironmentalReverb（AOSP 中 session 0 为 AUXILIARY，对外部播放器零听感）。
        if (sessionId > 0 && insertReverbSupported) {
            attachOrUpdateReverbLocked(sessionId, clampedScene);
        }

        // 可选 Virtualizer：严格先查 queryEffects() + canVirtualize(STEREO, BINAURAL)，强度限幅 150-400/1000。
        if (virtualizerSupported) {
            attachOrUpdateVirtualizerLocked(sessionId, clampedScene);
        }

        setStatusLocked(globalFallback ? Status.ACTIVE_GLOBAL : Status.ACTIVE_SESSION);
    }

    private void applySceneInPlaceLocked(int sessionId, int scene) {
        if (dynamics != null) {
            try {
                float[] gains = SCENE_EQ_GAINS[scene];
                dynamics.setInputGainAllChannelsTo(computeHeadroomDb(gains));
                dynamics.setPreEqAllChannelsTo(buildPreEq(gains));
                attachedScene = scene;
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed updating DynamicsProcessing scene in place", e);
            }
        }
        if (sessionId > 0 && insertReverbSupported) {
            attachOrUpdateReverbLocked(sessionId, scene);
        }
        if (virtualizerSupported) {
            attachOrUpdateVirtualizerLocked(sessionId, scene);
        }
    }

    private void attachOrUpdateReverbLocked(int sessionId, int scene) {
        if (sessionId <= 0 || !insertReverbSupported) {
            safeRelease(reverb);
            reverb = null;
            return;
        }
        if (scene == SCENE_WIDE) {
            // Natural Wide 关闭插入式混响，保持干净声场
            safeRelease(reverb);
            reverb = null;
            return;
        }
        try {
            if (reverb == null) {
                EnvironmentalReverb rev = new EnvironmentalReverb(0, sessionId);
                if (!rev.hasControl()) {
                    safeRelease(rev);
                    return;
                }
                reverb = rev;
            }
            configureReverbForScene(reverb, scene);
            reverb.setEnabled(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "Insert EnvironmentalReverb unavailable on session " + sessionId, e);
            safeRelease(reverb);
            reverb = null;
        }
    }

    private static void configureReverbForScene(EnvironmentalReverb rev, int scene) {
        // OpenSL ES / AOSP 默认 roomLevel = -10000 mB（静音），必须抬升至 0 mB 才能让反射/混响参数生效。
        rev.setRoomLevel((short) 0);
        if (scene == SCENE_CINEMA) {
            rev.setReflectionsDelay(18);
            rev.setReflectionsLevel((short) -900);
            rev.setReverbDelay(32);
            rev.setReverbLevel((short) -1400);
            rev.setDecayTime(850);
            rev.setDecayHFRatio((short) 650);
            rev.setRoomHFLevel((short) -400);
            rev.setDiffusion((short) 850);
            rev.setDensity((short) 800);
        } else if (scene == SCENE_CONCERT) {
            rev.setReflectionsDelay(26);
            rev.setReflectionsLevel((short) -700);
            rev.setReverbDelay(48);
            rev.setReverbLevel((short) -1100);
            rev.setDecayTime(1600);
            rev.setDecayHFRatio((short) 550);
            rev.setRoomHFLevel((short) -600);
            rev.setDiffusion((short) 950);
            rev.setDensity((short) 900);
        } else {
            // SCENE_STUDIO
            rev.setReflectionsDelay(8);
            rev.setReflectionsLevel((short) -1100);
            rev.setReverbDelay(16);
            rev.setReverbLevel((short) -2000);
            rev.setDecayTime(380);
            rev.setDecayHFRatio((short) 750);
            rev.setRoomHFLevel((short) -200);
            rev.setDiffusion((short) 700);
            rev.setDensity((short) 700);
        }
    }

    private void attachOrUpdateVirtualizerLocked(int sessionId, int scene) {
        try {
            if (virtualizer == null) {
                Virtualizer virt = new Virtualizer(0, sessionId);
                if (!virt.hasControl()
                        || !virt.getStrengthSupported()
                        || !virt.canVirtualize(AudioFormat.CHANNEL_OUT_STEREO,
                                Virtualizer.VIRTUALIZATION_MODE_BINAURAL)) {
                    safeRelease(virt);
                    return;
                }
                try {
                    virt.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL);
                } catch (RuntimeException ignored) {
                    // 部分 HAL 仅支持 AUTO 模式，降级继续设置限幅强度
                }
                virtualizer = virt;
            }
            virtualizer.setStrength(SCENE_VIRTUALIZER_STRENGTH[scene]);
            virtualizer.setEnabled(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "Optional Virtualizer unavailable on session " + sessionId, e);
            safeRelease(virtualizer);
            virtualizer = null;
        }
    }

    private static DynamicsProcessing.Config buildConfig(int scene) {
        float[] gains = SCENE_EQ_GAINS[scene];
        DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true, PRE_EQ_CUTOFFS.length,
                true, MBC_CUTOFFS.length,
                false, 0,
                true
        );
        builder.setPreferredFrameDuration(PREFERRED_FRAME_MS);
        builder.setInputGainAllChannelsTo(computeHeadroomDb(gains));
        builder.setPreEqAllChannelsTo(buildPreEq(gains));
        builder.setMbcAllChannelsTo(buildMbc());
        builder.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(
                true,
                true,
                0,
                LIMITER_ATTACK_MS,
                LIMITER_RELEASE_MS,
                LIMITER_RATIO,
                LIMITER_THRESHOLD_DB,
                0.0f
        ));
        return builder.build();
    }

    private static float computeHeadroomDb(float[] gains) {
        float maxBoost = 0f;
        for (float g : gains) {
            if (g > maxBoost) maxBoost = g;
        }
        return Math.min(INPUT_HEADROOM_DB, -maxBoost);
    }

    private static DynamicsProcessing.Eq buildPreEq(float[] gains) {
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, PRE_EQ_CUTOFFS.length);
        for (int i = 0; i < PRE_EQ_CUTOFFS.length; i++) {
            eq.setBand(i, new DynamicsProcessing.EqBand(true, PRE_EQ_CUTOFFS[i], gains[i]));
        }
        return eq;
    }

    private static DynamicsProcessing.Mbc buildMbc() {
        DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, true, MBC_CUTOFFS.length);
        for (int i = 0; i < MBC_CUTOFFS.length; i++) {
            mbc.setBand(i, new DynamicsProcessing.MbcBand(
                    true,
                    MBC_CUTOFFS[i],
                    MBC_ATTACK_MS,
                    MBC_RELEASE_MS,
                    MBC_RATIOS[i],
                    MBC_THRESHOLDS[i],
                    MBC_KNEE_DB,
                    -90.0f,
                    1.0f,
                    0.0f,
                    0.0f
            ));
        }
        return mbc;
    }

    private void probeEffectsOnceLocked() {
        if (effectsProbed) return;
        effectsProbed = true;
        insertReverbSupported = false;
        virtualizerSupported = false;
        try {
            AudioEffect.Descriptor[] descriptors = AudioEffect.queryEffects();
            if (descriptors == null) return;
            for (AudioEffect.Descriptor d : descriptors) {
                if (d == null || d.type == null) continue;
                if (AudioEffect.EFFECT_TYPE_ENV_REVERB.equals(d.type)
                        && AudioEffect.EFFECT_INSERT.equals(d.connectMode)) {
                    insertReverbSupported = true;
                } else if (AudioEffect.EFFECT_TYPE_VIRTUALIZER.equals(d.type)) {
                    virtualizerSupported = true;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "AudioEffect.queryEffects failed", e);
        }
    }

    private void registerMonitors() {
        if (monitorsRegistered) return;
        monitorsRegistered = true;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(sessionReceiver, filter);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed registering audio effect session receiver", e);
        }
        try {
            AudioManager am = audioManager();
            if (am != null) {
                am.registerAudioPlaybackCallback(playbackCallback, mainHandler);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed registering AudioPlaybackCallback", e);
        }
    }

    private void unregisterMonitors() {
        if (!monitorsRegistered) return;
        monitorsRegistered = false;
        try {
            app.unregisterReceiver(sessionReceiver);
        } catch (RuntimeException ignored) {
            // Receiver already unregistered
        }
        try {
            AudioManager am = audioManager();
            if (am != null) {
                am.unregisterAudioPlaybackCallback(playbackCallback);
            }
        } catch (RuntimeException ignored) {
            // Callback already unregistered
        }
    }

    private void refreshPlaybackStateLocked() {
        AudioManager am = audioManager();
        if (am == null) {
            playbackActive = false;
            return;
        }
        try {
            playbackActive = isBluetoothMediaPlaying(am, am.getActivePlaybackConfigurations());
        } catch (RuntimeException ignored) {
            playbackActive = false;
        }
    }

    private static boolean isBluetoothMediaPlaying(AudioManager am, List<AudioPlaybackConfiguration> configs) {
        if (configs == null || configs.isEmpty()) {
            return false;
        }
        boolean btOutCached = false;
        boolean btOutChecked = false;
        for (AudioPlaybackConfiguration cfg : configs) {
            if (cfg == null) continue;
            AudioAttributes attrs = cfg.getAudioAttributes();
            if (!isMediaAttributes(attrs)) continue;
            if (Build.VERSION.SDK_INT >= 33) {
                AudioDeviceInfo dev = Api33Playback.deviceInfo(cfg);
                if (dev != null) {
                    if (isBluetoothOutputType(dev.getType())) {
                        return true;
                    }
                    continue;
                }
            }
            if (!btOutChecked) {
                btOutChecked = true;
                btOutCached = hasBluetoothOutputDevice(am);
            }
            if (btOutCached) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMediaAttributes(AudioAttributes attrs) {
        if (attrs == null) return false;
        int usage = attrs.getUsage();
        return usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME;
    }

    private static boolean hasBluetoothOutputDevice(AudioManager am) {
        if (am == null) return false;
        try {
            AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (outputs == null) return false;
            for (AudioDeviceInfo dev : outputs) {
                if (dev != null && isBluetoothOutputType(dev.getType())) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static boolean isBluetoothOutputType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private int latestExternalSessionIdLocked() {
        int latest = 0;
        for (Map.Entry<Integer, String> entry : openSessions.entrySet()) {
            if (entry.getKey() != null && entry.getKey() > 0) {
                latest = entry.getKey();
            }
        }
        return latest;
    }

    private void releaseEffectsLocked() {
        safeRelease(virtualizer);
        virtualizer = null;
        safeRelease(reverb);
        reverb = null;
        safeRelease(dynamics);
        dynamics = null;
        attachedSessionId = -1;
        attachedScene = -1;
    }

    private static void safeRelease(AudioEffect effect) {
        if (effect == null) return;
        try {
            effect.setEnabled(false);
        } catch (RuntimeException ignored) {
            // Best-effort disable before release
        }
        try {
            effect.release();
        } catch (RuntimeException ignored) {
            // Best-effort native handle release
        }
    }

    private void scheduleIdleTimer() {
        mainHandler.removeCallbacks(idleReleaseTask);
        idleTimerScheduled = true;
        mainHandler.postDelayed(idleReleaseTask, IDLE_RELEASE_MS);
    }

    private void scheduleIdleTimerIfNeeded() {
        if (!idleTimerScheduled) {
            scheduleIdleTimer();
        }
    }

    private void cancelIdleTimer() {
        idleTimerScheduled = false;
        mainHandler.removeCallbacks(idleReleaseTask);
    }

    private void setStatusLocked(final Status next) {
        if (currentStatus == next) return;
        currentStatus = next;
        if (listeners.isEmpty()) return;
        final List<StatusListener> snapshot = new ArrayList<>(listeners);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (StatusListener l : snapshot) {
                    l.onSpatialStatusChanged(next);
                }
            }
        });
    }

    private AudioManager audioManager() {
        Object svc = app.getSystemService(Context.AUDIO_SERVICE);
        return svc instanceof AudioManager ? (AudioManager) svc : null;
    }

    private static int clampScene(int scene) {
        return Math.max(SCENE_WIDE, Math.min(SCENE_STUDIO, scene));
    }

    private static final class Api33Playback {
        static AudioDeviceInfo deviceInfo(AudioPlaybackConfiguration cfg) {
            try {
                return cfg.getAudioDeviceInfo();
            } catch (RuntimeException | NoSuchMethodError ignored) {
                return null;
            }
        }
    }
}

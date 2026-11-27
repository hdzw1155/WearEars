package com.hdz.wearcontrol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;

/**
 * 360° Spatial Lab &amp; Personal HRTF Calibrator.
 *
 * <p>Streams {@link WearEarsBinauralKernel} directly to a 48 kHz stereo {@link AudioTrack} with
 * {@code SPATIALIZATION_BEHAVIOR_NEVER} (API 32+) so OS Spatializer never double-processes our
 * binaural signal. Supports Watch7 rotary crown (15° detents + haptic tick), touch drag on both
 * watch and phone, persistent Head Width &amp; Pinna Notch calibration via {@link AppPrefs}, and
 * {@link VolumeGuard} >70% hearing safety attenuation (-12 dBFS).
 */
public class SpatialLabActivity extends Activity {

    private static final String TAG = "SpatialLab";
    private static final float CROWN_STEP_DEG = 15.0f;
    private static final int HEAD_WIDTH_STEP_PCT = 5;
    private static final int PINNA_NOTCH_STEP_HZ = 250;
    private static final float VOL_CAP_GAIN = 0.25f; // -12 dBFS
    private static final long VOL_POLL_MS = 250L;
    private static final long MANUAL_FADE_TIMEOUT_NS = 250_000_000L;
    private static final Object AUDIO_OUTPUT_LOCK = new Object();

    private static final int[] SCENE_IDS = {
            R.id.spatial_scene_wide,
            R.id.spatial_scene_cinema,
            R.id.spatial_scene_concert,
            R.id.spatial_scene_studio
    };

    public static Intent intent(Context ctx) {
        return new Intent(ctx, SpatialLabActivity.class);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int uiStyle;
    private AppScrollView scrollView;
    private ArcIndicatorView indicator;
    private SpatialLabDialView dialView;
    private TextView azimuthLabel;
    private TextView playBtn;
    private TextView volBanner;
    private TextView headWidthLabel;
    private TextView pinnaLabel;

    // Live DSP parameters shared with audio thread
    private volatile float liveAzimuthDeg = 0.0f;
    private volatile int liveScene = WearEarsBinauralKernel.SCENE_WIDE;
    private volatile int liveHeadWidthPct = WearEarsBinauralKernel.HEAD_WIDTH_DEFAULT_PCT;
    private volatile int livePinnaNotchHz = WearEarsBinauralKernel.PINNA_NOTCH_DEFAULT_HZ;
    private volatile float liveCeilingGain = 1.0f;

    // Playback selection is main-thread owned. Each worker has its own cancellation token.
    private PlaybackSession playback;
    private boolean restartPlayback;

    // Rotary scroll accumulator for smooth 15° watch crown steps
    private float rotaryAccum = 0.0f;

    private final Runnable volumePollTask = new Runnable() {
        @Override
        public void run() {
            refreshVolumeSafety();
            if (isPlaybackRequested()) {
                ui.postDelayed(this, VOL_POLL_MS);
            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppPrefs.hasDisclaimer(this)) {
            finish();
            return;
        }
        uiStyle = AppPrefs.uiStyle(this);
        setContentView(R.layout.activity_spatial_lab);
        applyPhoneWindowChrome();

        scrollView = (AppScrollView) findViewById(R.id.spatial_lab_scroll);
        indicator = (ArcIndicatorView) findViewById(R.id.spatial_lab_indicator);
        dialView = (SpatialLabDialView) findViewById(R.id.spatial_dial);
        azimuthLabel = (TextView) findViewById(R.id.spatial_azimuth_label);
        playBtn = (TextView) findViewById(R.id.spatial_btn_play);
        volBanner = (TextView) findViewById(R.id.spatial_vol_banner);
        headWidthLabel = (TextView) findViewById(R.id.spatial_head_width_label);
        pinnaLabel = (TextView) findViewById(R.id.spatial_pinna_label);
        applyUnifiedStyle();

        DeviceForm.styleScrollContainer(this, scrollView, indicator);
        if (indicator != null && scrollView != null && DeviceForm.isWatch(this)) {
            indicator.attachTo(scrollView);
        }

        // Enlarge dial slightly on rectangular phone screens for comfortable touch dragging
        if (dialView != null && DeviceForm.isPhone(this)) {
            ViewGroup.LayoutParams lp = dialView.getLayoutParams();
            if (lp != null) {
                int phoneDialPx = Math.round(216.0f * getResources().getDisplayMetrics().density);
                lp.width = phoneDialPx;
                lp.height = phoneDialPx;
                dialView.setLayoutParams(lp);
            }
        }

        // Load persisted personal HRTF calibration
        liveScene = AppPrefs.localSpatialScene(this);
        liveHeadWidthPct = AppPrefs.spatialHeadWidthPct(this);
        livePinnaNotchHz = AppPrefs.spatialPinnaNotchHz(this);
        liveAzimuthDeg = 0.0f;

        if (dialView != null) {
            dialView.setAzimuthDeg(liveAzimuthDeg);
            dialView.setOnAzimuthChangeListener(new SpatialLabDialView.OnAzimuthChangeListener() {
                @Override
                public void onAzimuthChanged(float azimuthDeg, boolean fromUser) {
                    liveAzimuthDeg = azimuthDeg;
                    updateAzimuthLabel();
                }
            });
        }

        if (playBtn != null) {
            playBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (isPlaybackRequested()) {
                        stopPlayback(false);
                    } else {
                        startPlayback();
                    }
                }
            });
        }

        for (int i = 0; i < SCENE_IDS.length; i++) {
            final int sceneVal = i;
            View chip = findViewById(SCENE_IDS[i]);
            if (chip != null) {
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        haptic(HapticFeedbackConstants.CLOCK_TICK);
                        liveScene = sceneVal;
                        AppPrefs.setLocalSpatialScene(SpatialLabActivity.this, sceneVal);
                        refreshSceneChips();
                    }
                });
            }
        }

        bindStepper(R.id.spatial_head_minus, -HEAD_WIDTH_STEP_PCT, true);
        bindStepper(R.id.spatial_head_plus, HEAD_WIDTH_STEP_PCT, true);
        bindStepper(R.id.spatial_pinna_minus, -PINNA_NOTCH_STEP_HZ, false);
        bindStepper(R.id.spatial_pinna_plus, PINNA_NOTCH_STEP_HZ, false);

        updateAzimuthLabel();
        refreshSceneChips();
        refreshCalibrationLabels();
        refreshPlayButton();
        refreshVolumeSafety();
        applyPhoneInsets();
    }

    private void bindStepper(int viewId, final int delta, final boolean isHeadWidth) {
        View btn = findViewById(viewId);
        if (btn == null) {
            return;
        }
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                haptic(HapticFeedbackConstants.CLOCK_TICK);
                if (isHeadWidth) {
                    int next = clampInt(liveHeadWidthPct + delta,
                            WearEarsBinauralKernel.HEAD_WIDTH_MIN_PCT,
                            WearEarsBinauralKernel.HEAD_WIDTH_MAX_PCT);
                    if (next != liveHeadWidthPct) {
                        liveHeadWidthPct = next;
                        AppPrefs.setSpatialHeadWidthPct(SpatialLabActivity.this, next);
                        refreshCalibrationLabels();
                    }
                } else {
                    int next = clampInt(livePinnaNotchHz + delta,
                            WearEarsBinauralKernel.PINNA_NOTCH_MIN_HZ,
                            WearEarsBinauralKernel.PINNA_NOTCH_MAX_HZ);
                    if (next != livePinnaNotchHz) {
                        livePinnaNotchHz = next;
                        AppPrefs.setSpatialPinnaNotchHz(SpatialLabActivity.this, next);
                        refreshCalibrationLabels();
                    }
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scrollView != null) scrollView.setActive(true);
        if (indicator != null && DeviceForm.isWatch(this)) indicator.setActive(true);
        refreshVolumeSafety();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scrollView != null) scrollView.setActive(false);
        if (indicator != null && DeviceForm.isWatch(this)) indicator.setActive(false);
        stopPlayback(true);
    }

    @Override
    protected void onDestroy() {
        stopPlayback(true);
        super.onDestroy();
    }

    /**
     * Intercept Watch7 rotary encoder before AppScrollView consumes it so the crown/bezel
     * directly orbits the 360° sound source in 15° steps while touch swipes still scroll.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev != null && (ev.getSource() & android.view.InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            if (handleRotaryMotion(ev)) {
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    /**
     * Watch7 rotary crown / bezel rotates the 360° sound source in 15° steps with haptic tick.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (handleRotaryMotion(ev)) {
            return true;
        }
        return super.onGenericMotionEvent(ev);
    }

    private boolean handleRotaryMotion(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL || action == MotionEvent.ACTION_HOVER_MOVE) {
            float delta = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta == 0.0f) {
                delta = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            }
            if (delta == 0.0f) {
                delta = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            }
            if (delta != 0.0f) {
                // On Wear OS / Samsung rotary, negative AXIS_SCROLL is clockwise (scroll down)
                rotaryAccum += (-delta);
                int steps = Math.round(rotaryAccum);
                if (steps == 0 && Math.abs(rotaryAccum) >= 0.35f) {
                    steps = rotaryAccum > 0.0f ? 1 : -1;
                }
                if (steps != 0) {
                    rotaryAccum = 0.0f;
                    stepAzimuth(steps * CROWN_STEP_DEG);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_NAVIGATE_NEXT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            stepAzimuth(CROWN_STEP_DEG);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_NAVIGATE_PREVIOUS || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            stepAzimuth(-CROWN_STEP_DEG);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void stepAzimuth(float deltaDeg) {
        float next = (liveAzimuthDeg + deltaDeg) % 360.0f;
        if (next < 0.0f) {
            next += 360.0f;
        }
        liveAzimuthDeg = next;
        if (dialView != null) {
            dialView.setAzimuthDeg(next);
        }
        updateAzimuthLabel();
        haptic(HapticFeedbackConstants.CLOCK_TICK);
    }

    private boolean isPlaybackRequested() {
        return playback != null && (!playback.cancelled || restartPlayback);
    }

    private void startPlayback() {
        if (playback != null) {
            // Restart after the previous track has been released, even on rapid taps.
            restartPlayback = true;
            playback.cancel(true);
            refreshPlayButton();
            return;
        }
        refreshVolumeSafety();
        playback = new PlaybackSession();
        playback.worker.start();
        refreshPlayButton();
        ui.removeCallbacks(volumePollTask);
        ui.postDelayed(volumePollTask, VOL_POLL_MS);
    }

    private void stopPlayback(boolean immediate) {
        ui.removeCallbacks(volumePollTask);
        restartPlayback = false;
        if (playback != null) playback.cancel(immediate);
        refreshPlayButton();
    }

    private final class PlaybackSession {
        final Thread worker = new Thread(() -> runPlayback(this), "WearEars-Binaural");
        volatile boolean cancelled;
        private volatile long stopDeadlineNs;
        // Owned exclusively by this session's audio thread.
        private AudioTrack track;

        void cancel(boolean immediate) {
            if (immediate || !cancelled) {
                stopDeadlineNs = System.nanoTime() + (immediate ? 0L : MANUAL_FADE_TIMEOUT_NS);
                cancelled = true;
            }
            if (immediate) worker.interrupt();
        }

        boolean shouldStopNow() {
            return worker.isInterrupted() || (cancelled && System.nanoTime() - stopDeadlineNs >= 0);
        }
    }

    private void runPlayback(PlaybackSession session) {
        try {
            // Only audio workers acquire this lock, including after Activity recreation.
            synchronized (AUDIO_OUTPUT_LOCK) {
                if (session.cancelled) return;
                WearEarsSpatialEffect effect = WearEarsSpatialEffect.get(this);
                try {
                    effect.setLabActive(this, true);
                    runAudioLoop(session);
                } finally {
                    releaseTrack(session.track);
                    session.track = null;
                    effect.setLabActive(this, false);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            Log.w(TAG, "AudioTrack playback error", error);
        } finally {
            ui.post(() -> onPlaybackFinished(session));
        }
    }

    private void runAudioLoop(PlaybackSession session) throws InterruptedException {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (RuntimeException ignored) {
        }

        final int sampleRate = WearEarsBinauralKernel.SAMPLE_RATE;
        final int block = WearEarsBinauralKernel.BLOCK_SIZE;
        final int minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.w(TAG, "AudioTrack minBufferSize invalid: " + minBuf);
            return;
        }
        final int bufBytes = Math.max(minBuf, block * 4 * 4);

        AudioAttributes.Builder attrBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        if (Build.VERSION.SDK_INT >= 32) {
            attrBuilder.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER);
        }

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrBuilder.build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        session.track = track;
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack failed to initialize");
            return;
        }

        WearEarsBinauralKernel kernel = new WearEarsBinauralKernel();
        kernel.setAzimuthDeg(liveAzimuthDeg);
        kernel.setScene(liveScene);
        kernel.setHeadWidthPct(liveHeadWidthPct);
        kernel.setPinnaNotchHz(livePinnaNotchHz);
        kernel.setOutputGainCeiling(liveCeilingGain);
        kernel.reset();
        kernel.resetAuditionGenerator();
        kernel.fadeIn();

        float[] mono = new float[block];
        float[] outL = new float[block];
        float[] outR = new float[block];
        short[] pcm = new short[block * 2];

        if (session.cancelled) return;
        track.play();
        boolean fadingOut = false;
        while (!session.shouldStopNow()) {
            if (session.cancelled && !fadingOut) {
                kernel.fadeOut();
                fadingOut = true;
            }

            kernel.setAzimuthDeg(liveAzimuthDeg);
            kernel.setScene(liveScene);
            kernel.setHeadWidthPct(liveHeadWidthPct);
            kernel.setPinnaNotchHz(livePinnaNotchHz);
            kernel.setOutputGainCeiling(liveCeilingGain);
            kernel.fillAuditionSource(mono, block, WearEarsBinauralKernel.AUDITION_CHORD_TEXTURE);
            kernel.processMono(mono, outL, outR, block);

            for (int i = 0; i < block; i++) {
                pcm[i * 2] = floatToPcm16(outL[i]);
                pcm[i * 2 + 1] = floatToPcm16(outR[i]);
            }

            // Non-blocking writes let cancellation reach the owning worker even on a stalled track.
            int offset = 0;
            while (offset < pcm.length && !session.shouldStopNow()) {
                int written = track.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                if (written < 0) return;
                if (written == 0) Thread.sleep(4L);
                offset += written;
            }
            if (fadingOut && kernel.isFadedOut()) break;
        }
    }

    private static void releaseTrack(AudioTrack track) {
        if (track == null) return;
        try {
            track.pause();
            track.flush();
            track.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            track.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void onPlaybackFinished(PlaybackSession session) {
        if (playback != session) return;
        playback = null;
        ui.removeCallbacks(volumePollTask);
        if (restartPlayback) {
            restartPlayback = false;
            startPlayback();
        } else if (!isDestroyed()) {
            refreshPlayButton();
        }
    }

    /**
     * Hearing safety check aligned with {@link VolumeGuard}'s exact 70% boundary ({@code max * 7L / 10L}).
     * When media volume exceeds 70%, automatically caps internal test gain to -12 dBFS (0.25f)
     * and displays the safety banner.
     */
    private void refreshVolumeSafety() {
        boolean capped = false;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int boundary = (int) (max * 7L / 10L);
                capped = max > 0 && cur > boundary;
            }
        } catch (RuntimeException ignored) {
        }
        liveCeilingGain = capped ? VOL_CAP_GAIN : 1.0f;
        if (volBanner != null) {
            volBanner.setVisibility(capped ? View.VISIBLE : View.GONE);
        }
    }

    private void updateAzimuthLabel() {
        if (azimuthLabel != null) {
            int rounded = Math.round(liveAzimuthDeg) % 360;
            if (rounded < 0) rounded += 360;
            azimuthLabel.setText(getString(R.string.spatial_lab_azimuth, rounded));
        }
    }

    private void applyUnifiedStyle() {
        View root = findViewById(R.id.spatial_lab_root);
        if (root != null) {
            root.setBackgroundColor(NeoStyle.pageBg(uiStyle));
        }
        int textPrimary = NeoStyle.textPrimary(uiStyle);
        int textDim = NeoStyle.textDim(uiStyle);
        int textFaint = NeoStyle.textFaint(uiStyle);
        int cardBg = NeoStyle.cardDrawable(uiStyle);

        TextView title = (TextView) findViewById(R.id.spatial_lab_title);
        if (title != null) title.setTextColor(textPrimary);
        TextView sub = (TextView) findViewById(R.id.spatial_lab_sub);
        if (sub != null) sub.setTextColor(textDim);
        if (azimuthLabel != null) azimuthLabel.setTextColor(textPrimary);
        if (volBanner != null) {
            volBanner.setBackgroundResource(NeoStyle.warnBannerBg(uiStyle));
            volBanner.setTextColor(NeoStyle.warnTextColor(uiStyle));
        }
        if (dialView != null) {
            dialView.applyUiStyle(uiStyle);
        }
        View sceneCard = findViewById(R.id.spatial_scene_card);
        if (sceneCard != null) sceneCard.setBackgroundResource(cardBg);
        TextView sceneLabel = (TextView) findViewById(R.id.spatial_scene_header);
        if (sceneLabel != null) sceneLabel.setTextColor(textFaint);
        View calCard = findViewById(R.id.spatial_calibration_card);
        if (calCard != null) calCard.setBackgroundResource(cardBg);
        if (headWidthLabel != null) headWidthLabel.setTextColor(textDim);
        if (pinnaLabel != null) pinnaLabel.setTextColor(textDim);
        int[] steppers = {
                R.id.spatial_head_minus, R.id.spatial_head_plus,
                R.id.spatial_pinna_minus, R.id.spatial_pinna_plus
        };
        for (int id : steppers) {
            TextView tv = (TextView) findViewById(id);
            if (tv != null) {
                NeoStyle.styleChip(tv, uiStyle, false);
                tv.setTextColor(textPrimary);
            }
        }
    }

    private void refreshPlayButton() {
        boolean active = isPlaybackRequested();
        if (playBtn != null) {
            playBtn.setText(active ? R.string.spatial_lab_stop : R.string.spatial_lab_play);
            if (active) {
                playBtn.setBackgroundResource(NeoStyle.pillDrawable(uiStyle));
                playBtn.setTextColor(NeoStyle.textPrimary(uiStyle));
            } else {
                playBtn.setBackgroundResource(NeoStyle.ctaDrawable(uiStyle));
                playBtn.setTextColor(NeoStyle.ctaTextColor(uiStyle));
            }
        }
        if (dialView != null) {
            dialView.setPlaying(active);
        }
    }

    private void refreshSceneChips() {
        for (int i = 0; i < SCENE_IDS.length; i++) {
            TextView tv = (TextView) findViewById(SCENE_IDS[i]);
            if (tv != null) {
                NeoStyle.styleChip(tv, uiStyle, i == liveScene);
            }
        }
    }

    private void refreshCalibrationLabels() {
        if (headWidthLabel != null) {
            headWidthLabel.setText(getString(R.string.spatial_lab_head_width, liveHeadWidthPct));
        }
        if (pinnaLabel != null) {
            pinnaLabel.setText(getString(R.string.spatial_lab_pinna, livePinnaNotchHz));
        }
    }

    private void applyPhoneWindowChrome() {
        if (getResources().getConfiguration().isScreenRound()) {
            return;
        }
        boolean light = NeoStyle.light(uiStyle);
        int barCol = NeoStyle.pageBg(uiStyle);
        getWindow().setStatusBarColor(barCol);
        getWindow().setNavigationBarColor(barCol);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        decor.setSystemUiVisibility(flags);
    }

    private void applyPhoneInsets() {
        DeviceForm.styleScrollContainer(this, scrollView, indicator);
        if (getResources().getConfiguration().isScreenRound()) {
            return;
        }
        final View root = findViewById(android.R.id.content);
        final View content = findViewById(R.id.spatial_lab_content);
        if (root == null || content == null) {
            return;
        }
        final int baseTop = content.getPaddingTop();
        final int baseBot = content.getPaddingBottom();
        final int phoneSide = Math.round(24.0f * getResources().getDisplayMetrics().density);
        final int maxColPx = DeviceForm.isPhone(this) ? DeviceForm.phoneColumnWidthPx(this) : 0;
        final int[] lastInsets = new int[4];
        final Runnable syncPadding = new Runnable() {
            @Override
            public void run() {
                int avail = root.getWidth() > 0
                        ? root.getWidth()
                        : getResources().getDisplayMetrics().widthPixels;
                int contentAvail = Math.max(0, avail - lastInsets[2] - lastInsets[3]);
                int side = maxColPx > 0
                        ? Math.max(phoneSide, (contentAvail - maxColPx) / 2)
                        : phoneSide;
                int padL = lastInsets[2] + side;
                int padT = baseTop + lastInsets[0];
                int padR = lastInsets[3] + side;
                int padB = baseBot + lastInsets[1];
                if (content.getPaddingLeft() != padL || content.getPaddingTop() != padT
                        || content.getPaddingRight() != padR || content.getPaddingBottom() != padB) {
                    content.setPadding(padL, padT, padR, padB);
                }
            }
        };
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                lastInsets[0] = insets.getSystemWindowInsetTop();
                lastInsets[1] = insets.getSystemWindowInsetBottom();
                lastInsets[2] = insets.getSystemWindowInsetLeft();
                lastInsets[3] = insets.getSystemWindowInsetRight();
                syncPadding.run();
                if (indicator != null && indicator.getVisibility() == View.VISIBLE) {
                    indicator.setPadding(0, lastInsets[0], 0, lastInsets[1]);
                }
                return insets;
            }
        });
        if (maxColPx > 0) {
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int oldL, int oldT, int oldR, int oldB) {
                    if ((r - l) != (oldR - oldL)) {
                        syncPadding.run();
                    }
                }
            });
        }
        root.requestApplyInsets();
    }

    private void haptic(int kind) {
        try {
            getWindow().getDecorView().performHapticFeedback(kind);
        } catch (RuntimeException ignored) {
        }
    }

    private static short floatToPcm16(float sample) {
        float clamped = sample < -1.0f ? -1.0f : (sample > 1.0f ? 1.0f : sample);
        return (short) Math.round(clamped * 32767.0f);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

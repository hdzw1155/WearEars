package com.hdz.wearcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/**
 * 主界面: 状态头 + 电量环卡 + 噪声控制卡 + 开发者设置(隐藏) + 折叠的免责声明.
 *
 * <p>交互回执约定(与磁贴共用同一批字符串):
 * 主状态行只由"连接域"决定(连接中/已连接/未连接/蓝牙未开/无权限/没找到耳机/连接失败),
 * 机型、低电量、充电、切换成功等都只改副行或小芯片, 不顶掉主行.
 */
public class MainActivity extends Activity implements TwsClient.Listener {

    private static final String TAG = "MainActivity";

    private static final int REQ_PERM = 1;
    private static final long TAP_RESET_MS = 2000L;
    private static final long ECHO_GUARD_MS = 4000L;
    private static final long MODE_ACK_TIMEOUT_MS = 2500L;
    private static final long TEMP_SUB_MS = 1600L;
    private static final long RECONNECT_DELAY_MS = 3000L;
    /** 开发者功能(空间音频/DeepX)的点击冷却: 1000ms 内重复点击直接忽略, 用代码防抖替代常驻文字警告 */
    private static final long DEBOUNCE_MS = 1000L;
    private static final int MAX_AUTO_RECONNECT = 3;

    // 视觉令牌 (与 res/values/colors.xml 保持一致; 代码里要动态给自绘控件上色)
    private static final int COL_ACCENT = 0xFF2B6CB0;
    private static final int COL_DIM = 0x99F2F2F5;
    private static final int COL_ICON = 0x99F2F2F5;
    private static final int COL_OK = 0xFF4CC38A;
    private static final int COL_WARN = 0xFFF5A524;
    private static final int COL_DANGER = 0xFFF0524F;
    private static final int COL_IDLE = 0x61F2F2F5;
    private static final int COL_TEXT = 0xFFF2F2F5;

    private static final int[] DEEPX_NAME_RES = {
            R.string.deepx_std, R.string.deepx_vocal, R.string.deepx_bass,
            R.string.deepx_treble, R.string.deepx_book};
    private static final int[] DEEPX_MODES = {0, 1, 2, 3, 5};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TwsClient client;
    /** 监听蓝牙开关: 用户从快捷面板打开蓝牙后立刻重连, 不用等 30 秒轮询 */
    private BroadcastReceiver btReceiver;
    /** 旋转表冠/触摸表圈的滚动容器(事件若没人消费会冒泡到 Activity, 由这里接住) */
    private AppScrollView scrollView;
    /** 贴屏幕右缘的弧形指示条: 独立覆盖层, 不随内容滚动 */
    private ArcIndicatorView indicator;

    private StatusDotView dot;
    private TextView status;
    private TextView sub;
    private TextView chip;
    private View ctaReconnect;

    private TextView pctL;
    private TextView pctR;
    private TextView pctC;
    private BatteryBarView barL;
    private BatteryBarView barR;
    private BatteryBarView barC;
    private View boltL;
    private View boltR;
    private View boltC;

    /** 经典界面的三只「压扁圆环」; 新版界面里为 null */
    private BatteryBlockView blockL;
    private BatteryBlockView blockR;
    private BatteryBlockView blockC;
    /** 经典=压扁圆角矩形轮廓环; 液位=块内液位填充 */
    private BatteryRectRingView ringL;
    private BatteryRectRingView ringR;
    private BatteryRectRingView ringC;
    private View ringRow;
    private View blockRow;
    private View classicBatteryCard;
    private View cardL;
    private View cardR;
    private View cardC;
    private View batteryHeader;

    private NoiseSegmentedView noiseSeg;
    private SnapChipRow deepxScroller;

    /** 经典界面: 备份版布局 + 旧配色, 唯一差别是电量从圆环压扁成圆角矩形轮廓 */
    private boolean classic;
    private static final int CL_DIM = 0xFF8E8E93;

    /** 当前界面风格/语言: onResume 里与之对比, 变了就 recreate 立即生效 */
    private int curUiStyle;
    private String curLang;

    private Switch swSpatial;
    private TextView spatialHint;
    private TextView deepxCurrent;
    private TextView[] deepxChips;

    private View devSection;
    private View devHint;
    private View disclaimerRow;

    private int lastMode = -1;
    private int pendingMode = -1;
    private long connectStartAt = 0;
    private Runnable refresher;
    private Runnable reconnectTask;
    private Runnable tempSubTask;
    private Runnable modeAckTimeout;
    private int reconnectAttempts = 0;
    private boolean connecting;
    private String deviceName = "";
    private boolean lowReported = false;

    /** 当前驱动是否只有"整机总电量"(标准协议兜底/单电池头戴): 有的话电量区只显示一格 */
    private volatile boolean singleBatt;
    private TextView lblL;
    private TextView lblR;
    private TextView lblC;
    /** 噪声/空间音频/DeepX 三张卡: 按驱动能力位整卡隐藏, 不露出点了没反应的控件 */
    private View noiseCard;
    private View spatialCard;
    private View deepxCard;

    private boolean spatialGuard;
    private long spatialSetAt = 0;
    private int deepxMode = -1;
    private long deepxSetAt = 0;
    private Runnable deepxPendingTask;

    private int devTaps = 0;
    private long lastTapAt = 0;
    private long lastSpatialAt = 0;
    private long lastDeepxAt = 0;
    private boolean disclaimerDialogUp = false;

    /** 主界面是否在前台(onResume/onPause 维护; 断开后的自动重连只在界面前台时投任务) */
    static volatile boolean resumed = false;

    // ---- 主状态行当前值, 供临时副行结束后恢复 ----
    private int stateRes = R.string.status_idle;
    private int stateColor = COL_IDLE;
    private boolean stateHollow = true;
    private boolean stateBreathing = false;
    private String stateSub = "";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        curUiStyle = AppPrefs.uiStyle(this);
        curLang = AppPrefs.lang(this);
        classic = curUiStyle == AppPrefs.STYLE_CLASSIC;
        setContentView(R.layout.activity_main);

        scrollView = (AppScrollView) findViewById(R.id.scroll);
        scrollView.setFocusableInTouchMode(true);
        scrollView.requestFocus();
        indicator = (ArcIndicatorView) findViewById(R.id.indicator);
        if (indicator != null) {
            indicator.attachTo(scrollView);
        }

        dot = (StatusDotView) findViewById(R.id.status_dot);
        dot.setContentDescription(getString(R.string.status_idle));
        status = (TextView) findViewById(R.id.status);
        sub = (TextView) findViewById(R.id.sub);
        chip = (TextView) findViewById(R.id.status_chip);
        ctaReconnect = findViewById(R.id.cta_reconnect);

        pctL = (TextView) findViewById(R.id.pct_l);
        pctR = (TextView) findViewById(R.id.pct_r);
        pctC = (TextView) findViewById(R.id.pct_c);
        barL = (BatteryBarView) findViewById(R.id.bar_l);
        barR = (BatteryBarView) findViewById(R.id.bar_r);
        barC = (BatteryBarView) findViewById(R.id.bar_c);
        boltL = findViewById(R.id.bolt_l);
        boltR = findViewById(R.id.bolt_r);
        boltC = findViewById(R.id.bolt_c);

        blockL = (BatteryBlockView) findViewById(R.id.block_l);
        blockR = (BatteryBlockView) findViewById(R.id.block_r);
        blockC = (BatteryBlockView) findViewById(R.id.block_c);
        ringL = (BatteryRectRingView) findViewById(R.id.ring_l);
        ringR = (BatteryRectRingView) findViewById(R.id.ring_r);
        ringC = (BatteryRectRingView) findViewById(R.id.ring_c);
        ringRow = findViewById(R.id.ring_row);
        if (ringL != null) ringL.setCornerRadiusDp(14f);
        if (ringR != null) ringR.setCornerRadiusDp(14f);
        if (ringC != null) ringC.setCornerRadiusDp(14f);
        blockRow = findViewById(R.id.block_row);
        classicBatteryCard = findViewById(R.id.battery_classic_card);
        batteryHeader = findViewById(R.id.battery_header);
        View refreshCard = findViewById(R.id.refresh_card);
        if (refreshCard != null) {
            refreshCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onRefreshTapped();
                }
            });
        }
        cardL = findViewById(R.id.card_l);
        cardR = findViewById(R.id.card_r);
        cardC = findViewById(R.id.card_c);

        noiseSeg = (NoiseSegmentedView) findViewById(R.id.noise_seg);
        deepxScroller = (SnapChipRow) findViewById(R.id.deepx_scroller);

        // 电量区双形态: 新版横向进度条 / 经典压扁圆环, 其余 UI 完全一致
        applyBatteryStyle();

        swSpatial = (Switch) findViewById(R.id.switch_spatial);
        spatialHint = (TextView) findViewById(R.id.spatial_hint);
        deepxCurrent = (TextView) findViewById(R.id.deepx_current);
        devSection = findViewById(R.id.dev_section);
        devHint = findViewById(R.id.dev_hint);
        disclaimerRow = findViewById(R.id.disclaimer_row);
        lblL = (TextView) findViewById(R.id.lbl_l);
        lblR = (TextView) findViewById(R.id.lbl_r);
        lblC = (TextView) findViewById(R.id.lbl_c);
        // 三张卡在布局里没有 id: 从子控件往上找
        noiseCard = parentOf(noiseSeg, 1);
        spatialCard = parentOf(swSpatial, 2);
        deepxCard = parentOf(deepxCurrent, 2);

        deepxChips = new TextView[]{
                (TextView) findViewById(R.id.deepx_0),
                (TextView) findViewById(R.id.deepx_1),
                (TextView) findViewById(R.id.deepx_2),
                (TextView) findViewById(R.id.deepx_3),
                (TextView) findViewById(R.id.deepx_4)};
        ensureDeepxChips();


        View openSettings = findViewById(R.id.open_settings);
        if (openSettings != null) {
            openSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                    startActivity(SettingsActivity.intent(MainActivity.this));
                }
            });
        }

        wireListeners();
        entryAnimation();
        registerBtReceiver();
    }

    private void registerBtReceiver() {
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                int btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                if (btState == BluetoothAdapter.STATE_ON) {
                    if (resumed && (client == null || !client.isConnected())) {
                        tryConnect();
                    }
                } else if (btState == BluetoothAdapter.STATE_OFF) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            clearData();
                            setState(R.string.status_bt_off, COL_WARN, false, false,
                                    getString(R.string.sub_bt_off_action));
                        }
                    });
                }
            }
        };
        registerReceiver(btReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    /** 经典布局的 DeepX 行是空的: 按新版样式在代码里补 5 个 chip, 这样两套界面共用同一套渲染逻辑 */
    private void ensureDeepxChips() {
        if (deepxChips == null || deepxChips[0] != null) {
            return;
        }
        View row = findViewById(R.id.deepx_row);
        if (!(row instanceof LinearLayout)) {
            return;
        }
        LinearLayout ll = (LinearLayout) row;
        String[] names = deepxNames();
        for (int i = 0; i < names.length; i++) {
            TextView chip = new TextView(this);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(COL_DIM);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            chip.setGravity(Gravity.CENTER);
            int ph = (int) dp(12);
            int pv = (int) dp(8);
            chip.setPadding(ph, pv, ph, pv);
            chip.setMinWidth((int) dp(56));
            chip.setMinHeight((int) dp(40));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart((int) dp(i == 0 ? 2 : 6));
            chip.setLayoutParams(lp);
            chip.setText(names[i]);
            ll.addView(chip);
            deepxChips[i] = chip;
        }
    }

    private void wireListeners() {
        // 开发者设置: 默认关闭, 连点入口 5 下本会话内显示(不持久化)
        devHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long now = SystemClock.elapsedRealtime();
                devTaps = (now - lastTapAt > TAP_RESET_MS) ? 1 : devTaps + 1;
                lastTapAt = now;
                if (devTaps >= 5 && devSection.getVisibility() != View.VISIBLE) {
                    devSection.setVisibility(View.VISIBLE);
                    devSection.setAlpha(0f);
                    devSection.setTranslationY(dp(6));
                    devSection.animate().alpha(1f).translationY(0f)
                            .setDuration(220).start();
                    devHint.setVisibility(View.GONE);
                    haptic(HapticFeedbackConstants.CONFIRM);
                    Toast.makeText(MainActivity.this, R.string.dev_unlocked_toast,
                            Toast.LENGTH_SHORT).show();
                } else {
                    haptic(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
        });

        ctaReconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                clearData();
                tryConnect();
            }
        });

        // 免责声明已全文铺在页面底部, 点击不再弹窗
        disclaimerRow.setOnClickListener(null);

        findViewById(R.id.refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRefreshTapped();
            }
        });

        if (noiseSeg != null) {
            noiseSeg.setOnModeChangeListener(new NoiseSegmentedView.OnModeChangeListener() {
                @Override
                public void onModeSelected(int mode) {
                    setMode(mode);
                }
            });
        }

        for (int i = 0; i < deepxChips.length; i++) {
            final int index = i;
            deepxChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 1000ms 冷却: 冷却中整排 chip 半透明且不响应, 防止连续点击把耳机指令打乱
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastDeepxAt < DEBOUNCE_MS) return;
                    lastDeepxAt = now;
                    setDeepxChipsEnabled(false);
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            setDeepxChipsEnabled(true);
                        }
                    }, DEBOUNCE_MS);
                    setDeepX(index);
                }
            });
        }

        swSpatial.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                if (spatialGuard) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastSpatialAt < DEBOUNCE_MS) {
                    // 冷却中: 把开关拨回原状态, 不把抖动传给耳机
                    spatialGuard = true;
                    b.setChecked(!isChecked);
                    spatialGuard = false;
                    return;
                }
                lastSpatialAt = now;
                onSpatialToggled(isChecked);
            }
        });
    }

    /** 卡片一次性的入场: alpha + 轻微上移, 只在首次创建时跑, onResume 不重播 */
    private void entryAnimation() {
        View content = getWindow().getDecorView();
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(220).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页可能改了界面风格/语言: 回到主界面时重建一次, 立即生效
        int style = AppPrefs.uiStyle(this);
        String lang = AppPrefs.lang(this);
        if (style != curUiStyle || !lang.equals(curLang)) {
            recreate();
            return;
        }
        resumed = true;
        tryConnect();
        startRefresher();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacksAndMessages(null);
        connecting = false;
        dot.setBreathing(false);
        reconnectTask = null;
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (btReceiver != null) {
            try {
                unregisterReceiver(btReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            btReceiver = null;
        }
        super.onDestroy();
    }

    /**
     * 旋转表冠 / 触摸表圈: 系统把转动的刻度作为 ACTION_SCROLL 投递, 焦点视图没消费时会冒泡到
     * Activity 这一层(0.3.7 就是在这里接住的), 统一转给滚动容器做带缓动的滚动.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float delta = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            // 临时调试: 确认真表圈/触摸表圈的旋转事件有没有送进来(发版前删)
            Log.d(TAG, "rotary delta=" + delta + " src=" + ev.getSource());
            if (delta != 0f && scrollView != null && scrollView.handleRotary(delta)) {
                return true;
            }
        }
        return super.onGenericMotionEvent(ev);
    }

    /** 阶梯轮询省电: 连上先快查两轮拿首屏, 之后 30 秒慢轮询; 空间音频/DeepX 每 4 轮问一次 */
    private void startRefresher() {
        ui.removeCallbacks(refresher);
        refresher = new Runnable() {
            int tick = 0;

            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                tick++;
                try {
                    if (client != null && client.isConnected()) {
                        client.queryBattery();
                        client.queryNoise();
                        if (tick == 1 || tick % 4 == 0) {
                            client.querySpatial();
                            client.queryDeepX();
                        }
                    } else if (!connecting
                            && SystemClock.elapsedRealtime() - connectStartAt > 12000) {
                        tryConnect();
                    }
                } catch (RuntimeException e) {
                    // 轮询任务绝不能因为一次蓝牙异常就把整个界面崩掉: 记一笔日志, 下一轮继续
                    Log.e(TAG, "refresher tick failed", e);
                }
                ui.postDelayed(this, tick <= 2 ? 5000L : 30000L);
            }
        };
        ui.postDelayed(refresher, 5000);
    }

    // ---------------- 状态行 ----------------

    private void setState(int textRes, int color, boolean hollow, boolean breathing,
                          String subText) {
        stateRes = textRes;
        stateColor = color;
        stateHollow = hollow;
        stateBreathing = breathing;
        stateSub = subText == null ? "" : subText;
        dot.setDotState(color, hollow);
        dot.setBreathing(breathing);
        dot.setContentDescription(getString(textRes));
        status.setText(textRes);
        setSub(stateSub);
        ctaReconnect.setVisibility(textRes == R.string.status_idle
                ? View.VISIBLE : View.GONE);
        if (textRes == R.string.status_connected) {
            dot.pop();
        }
    }

    private void setSub(String text) {
        String t = text == null ? "" : text;
        if (!t.contentEquals(sub.getText())) {
            sub.setText(t);
        }
    }

    /** 临时副行(切换成功/失败/未连接提示), 到时自动回到当前状态的基础副行 */
    private void showTempSub(String text, long durationMs) {
        setSub(text);
        if (tempSubTask != null) {
            ui.removeCallbacks(tempSubTask);
        }
        tempSubTask = new Runnable() {
            @Override
            public void run() {
                tempSubTask = null;
                setSub(stateSub);
            }
        };
        ui.postDelayed(tempSubTask, durationMs);
    }

    private void setChip(String text) {
        if (text == null || text.length() == 0) {
            chip.setVisibility(View.GONE);
        } else {
            chip.setText(text);
            chip.setVisibility(View.VISIBLE);
        }
    }

    private void tryConnect() {
        if (!hasDisclaimer()) {
            showDisclaimerDialog(true);
            return;
        }
        if (!hasPerm()) {
            setState(R.string.status_no_perm, COL_DANGER, false, false,
                    getString(R.string.sub_no_perm_action));
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
            return;
        }
        if (client != null && client.isConnected()) return;
        if (client != null) {
            client.disconnect();
            client = null;
        }
        BluetoothAdapter adapter =
                ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            clearData();
            setState(R.string.status_bt_off, COL_WARN, false, false,
                    getString(R.string.sub_bt_off_action));
            return;
        }
        BluetoothDevice target = findTarget(adapter);
        if (target == null) {
            clearData();
            setState(R.string.status_no_device, COL_WARN, false, false,
                    getString(R.string.sub_no_device_action));
            return;
        }
        connecting = true;
        String name = target.getName();
        if (name != null) deviceName = name;
        setState(R.string.status_connecting, COL_ACCENT, false, true,
                deviceName.length() > 0 ? deviceName : null);
        connectStartAt = SystemClock.elapsedRealtime();
        // 多品牌: 驱动由名字匹配决定, 匹配不上用标准协议兜底(只读电量, 不碰私有协议)
        TwsClient c = DriverRegistry.create(this, target);
        if (c == null) {
            connecting = false;
            setState(R.string.status_conn_failed, COL_DANGER, false, false, "");
            return;
        }
        c.addListener(this);
        client = c;
        c.connect(target);
    }

    private void onRefreshTapped() {
        haptic(HapticFeedbackConstants.CLOCK_TICK);
        if (client != null && client.isConnected()) {
            showTempSub(getString(R.string.sub_refreshing), 3000L);
            client.queryBattery();
            client.queryNoise();
            return;
        }
        showTempSub(getString(R.string.sub_not_connected), TEMP_SUB_MS);
        tryConnect();
    }

    private void setMode(int mode) {
        haptic(HapticFeedbackConstants.KEYBOARD_TAP);
        if (client == null || !client.isConnected()) {
            showTempSub(getString(R.string.sub_not_connected), TEMP_SUB_MS);
            tryConnect();
            return;
        }
        pendingMode = mode;
        renderMode();
        client.setNoiseMode(mode);
        if (modeAckTimeout != null) {
            ui.removeCallbacks(modeAckTimeout);
        }
        modeAckTimeout = new Runnable() {
            @Override
            public void run() {
                modeAckTimeout = null;
                pendingMode = -1;
                renderMode();
                showTempSub(getString(R.string.status_mode_failed), 2500L);
                haptic(HapticFeedbackConstants.REJECT);
            }
        };
        ui.postDelayed(modeAckTimeout, MODE_ACK_TIMEOUT_MS);
    }

    private void setDeepX(int index) {
        if (client == null || !client.isConnected()) {
            showTempSub(getString(R.string.sub_not_connected), TEMP_SUB_MS);
            tryConnect();
            return;
        }
        haptic(HapticFeedbackConstants.CLOCK_TICK);
        if (index < 0 || index >= DEEPX_MODES.length) return;
        deepxMode = index;
        deepxSetAt = SystemClock.elapsedRealtime();
        renderDeepx();
        deepxCurrent.setText(R.string.sub_deepx_pending);
        client.setDeepX(DEEPX_MODES[index]);
        if (deepxPendingTask != null) {
            ui.removeCallbacks(deepxPendingTask);
        }
        deepxPendingTask = new Runnable() {
            @Override
            public void run() {
                deepxPendingTask = null;
                deepxCurrent.setText(R.string.sub_unconfirmed);
                haptic(HapticFeedbackConstants.REJECT);
            }
        };
        ui.postDelayed(deepxPendingTask, 7000L);
    }

    private void onSpatialToggled(boolean isChecked) {
        if (spatialGuard) return;
        spatialSetAt = SystemClock.elapsedRealtime();
        if (isChecked && !getSharedPreferences(TileStore.PREFS, MODE_PRIVATE)
                .getBoolean("spatial_ack", false)) {
            spatialGuard = true;
            swSpatial.setChecked(false);
            spatialGuard = false;
            showSpatialDialog();
            return;
        }
        haptic(isChecked ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.KEYBOARD_TAP);
        if (client != null && client.isConnected()) {
            if (isChecked) {
                spatialHint.setVisibility(View.VISIBLE);
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        spatialHint.setVisibility(View.GONE);
                    }
                }, 1200L);
            }
            client.setSpatial(isChecked);
        } else {
            showTempSub(getString(R.string.sub_not_connected), TEMP_SUB_MS);
            tryConnect();
        }
    }

    // ---------------- 弹窗 ----------------

    /** 全局免责: 首次/换 versionCode 必弹, 同版本不再打扰; 拒绝直接退出 */
    private void showDisclaimerDialog(boolean blockOnStart) {
        if (disclaimerDialogUp) return;
        disclaimerDialogUp = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.global_disclaimer_title)
                .setMessage(R.string.global_disclaimer_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_agree, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        getSharedPreferences(TileStore.PREFS, MODE_PRIVATE).edit()
                                .putInt("disclaimer_ack_vc", versionCode()).apply();
                        disclaimerDialogUp = false;
                        tryConnect();
                    }
                })
                .setNegativeButton(R.string.dialog_refuse, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        disclaimerDialogUp = false;
                        finish();
                    }
                })
                .show();
    }

    private boolean hasDisclaimer() {
        return getSharedPreferences(TileStore.PREFS, MODE_PRIVATE)
                .getInt("disclaimer_ack_vc", 0) == versionCode();
    }

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** 首次开启空间音频的个别化确认; 确认后长期有效, 不随版本重置 */
    private void showSpatialDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.spatial_dialog_title)
                .setMessage(R.string.spatial_dialog_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        getSharedPreferences(TileStore.PREFS, MODE_PRIVATE).edit()
                                .putBoolean("spatial_ack", true).apply();
                        spatialSetAt = SystemClock.elapsedRealtime();
                        if (client != null && client.isConnected()) {
                            client.setSpatial(true);
                        }
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void onRefreshPermissionDenied() {
        setState(R.string.status_no_perm, COL_DANGER, false, false,
                getString(R.string.sub_no_perm_action));
        setSub(getString(R.string.sub_no_perm_action));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERM) {
            if (hasPerm()) {
                tryConnect();
            } else {
                onRefreshPermissionDenied();
            }
        }
    }

    // ---------------- 视图渲染 ----------------

    /** 当前应高亮的档位: 有待确认档时先显示目标档(回执失败再回落) */
    private int selectedModeForView() {
        return pendingMode >= 0 ? pendingMode : lastMode;
    }

    private void renderMode() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                final int mode = selectedModeForView();
                if (noiseSeg != null) {
                    noiseSeg.setMode(mode, true);
                }
            }
        });
    }

    private void renderDeepx() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < deepxChips.length; i++) {
                    boolean sel = (i == deepxMode);
                    deepxChips[i].setBackgroundResource(sel ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
                    deepxChips[i].setTextColor(sel ? Color.WHITE : COL_DIM);
                }
                if (deepxScroller != null) {
                    deepxScroller.select(deepxMode, true);
                }
                if (deepxMode >= 0 && deepxMode < DEEPX_NAME_RES.length) {
                    deepxCurrent.setText(deepxNameAt(deepxMode));
                } else {
                    deepxCurrent.setText("--");
                }
            }
        });
    }

    private void setBatteryUi(int left, int right, int box, int chargeFlags) {
        if (ringL != null) ringL.setBattery(left, (chargeFlags & 0x01) != 0);
        if (ringR != null) ringR.setBattery(right, (chargeFlags & 0x02) != 0);
        if (ringC != null) ringC.setBattery(box, (chargeFlags & 0x04) != 0);
        if (blockL != null) blockL.setBattery(left, (chargeFlags & 0x01) != 0);
        if (blockR != null) blockR.setBattery(right, (chargeFlags & 0x02) != 0);
        if (blockC != null) blockC.setBattery(box, (chargeFlags & 0x04) != 0);
        setCell(pctL, barL, boltL, left, (chargeFlags & 0x01) != 0, false);
        setCell(pctR, barR, boltR, right, (chargeFlags & 0x02) != 0, true);
        setCell(pctC, barC, boltC, box, (chargeFlags & 0x04) != 0, true);
    }

    /** 单电池设备(标准协议兜底/单电池头戴): 只有一格总电量, 借左耳位显示 */
    private void setSingleBatteryUi(int total) {
        setCell(pctL, barL, boltL, total, false, false);
    }

    /**
     * 按驱动能力位配置界面: 没降噪能力就整卡隐藏噪声控制, 没空间音频/DeepX 就隐藏对应卡。
     * 连上标准协议兜底时只剩电量卡, 界面不会出现"点了没反应"的控件。
     */
    private void applyBatteryConfig(int caps) {
        final boolean noise = (caps & TwsClient.CAP_NOISE) != 0;
        final boolean spatial = (caps & TwsClient.CAP_SPATIAL) != 0;
        final boolean deepx = (caps & TwsClient.CAP_DEEPX) != 0;
        final boolean single = (caps & TwsClient.CAP_BATTERY_SINGLE) != 0;
        singleBatt = single;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (noiseCard != null) {
                    noiseCard.setVisibility(noise ? View.VISIBLE : View.GONE);
                }
                if (spatialCard != null) {
                    spatialCard.setVisibility(spatial ? View.VISIBLE : View.GONE);
                }
                if (deepxCard != null) {
                    deepxCard.setVisibility(deepx ? View.VISIBLE : View.GONE);
                }
                if (lblL != null) {
                    lblL.setText(single ? R.string.label_total : R.string.label_left);
                }
                applyBatteryStyle();
            }
        });
    }

    /** 从某个子控件往上找第 n 层父容器(布局里卡片容器没 id, 只能这样拿) */
    private static View parentOf(View v, int levels) {
        View cur = v;
        for (int i = 0; i < levels && cur != null; i++) {
            android.view.ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return cur;
    }

    /**
     * 一格电量: 白色数字 + 「%」小一号 + 语义色进度条 + 充电小闪电.
     * percentFirst=true 时「%」放在数字左边(右耳/充电盒镜像排版), 否则保持「数字%」.
     * 未知(--)时数字用弱色、进度条只留底槽, 不假装 0%.
     */
    private void setCell(TextView pctView, BatteryBarView bar, View bolt, int pct, boolean charging,
                         boolean percentFirst) {
        boolean known = pct >= 0 && pct <= 100;
        if (pctView != null) {
            if (known) {
                String num = String.valueOf(pct);
                SpannableString sp = percentFirst
                        ? new SpannableString("%" + num)
                        : new SpannableString(num + "%");
                int unitStart = percentFirst ? 0 : num.length();
                sp.setSpan(new android.text.style.RelativeSizeSpan(0.58f), unitStart, unitStart + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                pctView.setText(sp);
                pctView.setTextColor(COL_TEXT);
            } else {
                pctView.setText("--");
                pctView.setTextColor(COL_IDLE);
            }
        }
        if (bar != null) {
            bar.setPercent(known ? pct : -1);
            int color;
            if (charging) {
                color = COL_WARN;
            } else if (known && pct <= 20) {
                color = COL_DANGER;
            } else {
                color = COL_OK;
            }
            bar.setFillColor(color);
        }
        if (bolt != null) {
            bolt.setVisibility(!classic && charging && known ? View.VISIBLE : View.GONE);
        }
    }

    /** DeepX 冷却期: 整排 chip 变半透明且不可点 */
    private void setDeepxChipsEnabled(boolean enabled) {
        for (TextView chip : deepxChips) {
            if (chip != null) {
                chip.setAlpha(enabled ? 1f : 0.45f);
                chip.setEnabled(enabled);
            }
        }
    }

    /** 未连接时清空电量、档位、芯片与临时提示 */
    private void clearData() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                setCell(pctL, barL, boltL, -1, false, false);
                setCell(pctR, barR, boltR, -1, false, true);
                setCell(pctC, barC, boltC, -1, false, true);
                if (ringL != null) ringL.setBattery(-1, false);
                if (ringR != null) ringR.setBattery(-1, false);
                if (ringC != null) ringC.setBattery(-1, false);
                if (blockL != null) blockL.setBattery(-1, false);
                if (blockR != null) blockR.setBattery(-1, false);
                if (blockC != null) blockC.setBattery(-1, false);
                lastMode = -1;
                pendingMode = -1;
                if (noiseSeg != null) {
                    noiseSeg.setMode(-1, false);
                }
                deepxMode = -1;
                renderDeepx();
                spatialGuard = true;
                swSpatial.setChecked(false);
                spatialGuard = false;
                setChip(null);
                lowReported = false;
            }
        });
    }

    // ---------------- TwsClient.Listener (后台线程回调) ----------------

    @Override
    public void onConnected(final String name) {
        connecting = false;
        reconnectAttempts = 0;
        final TwsClient c = client;
        final int caps = c != null ? c.caps() : 0;
        final int verif = c != null ? c.verifLevel() : TwsClient.VERIF_UNTESTED;
        // 磁贴靠 caps 决定显示三段电量/降噪轨还是单电量; 未验证驱动给个小标签, 功能照常尝试
        TileStore.saveName(this, name);
        TileStore.saveCaps(this, caps, verif);
        TileStore.noteOk(this);
        TileStore.push(this);
        ui.post(new Runnable() {
            @Override
            public void run() {
                deviceName = name == null ? "" : name;
                setState(R.string.status_connected, COL_OK, false, false, deviceName);
                applyBatteryConfig(caps);
                boolean untested = verif == TwsClient.VERIF_UNTESTED;
                setChip(untested ? getString(R.string.chip_unverified) : null);
                sub.setTextColor(untested ? COL_WARN : COL_DIM);
                if (untested) {
                    setSub(getString(R.string.sub_model_unverified));
                }
            }
        });
    }

    @Override
    public void onBattery(final int left, final int right, final int box, final int chargeFlags) {
        // 回调在驱动读线程: 电量数字的动画必须在主线程启动, 否则 ValueAnimator
        // 会抛 "Animators may only be run on Looper threads" 直接把进程崩掉(0.3.8 初版踩过)
        final TwsClient c = client;
        final boolean single = singleBatt
                || (c != null && (c.caps() & TwsClient.CAP_BATTERY_SINGLE) != 0);
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (single) {
                    setSingleBatteryUi(left);
                } else {
                    setBatteryUi(left, right, box, chargeFlags);
                }
            }
        });
        boolean minuteChanged = TileStore.noteOk(this);
        boolean batteryChanged = single
                ? TileStore.saveBatterySingle(this, left)
                : TileStore.saveBattery(this, left, right, box, chargeFlags);
        if (batteryChanged || minuteChanged) {
            TileStore.push(this);
        }
    }

    @Override
    public void onNoiseMode(final int mode) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                lastMode = mode;
                if (pendingMode == mode) {
                    pendingMode = -1;
                    if (modeAckTimeout != null) {
                        ui.removeCallbacks(modeAckTimeout);
                        modeAckTimeout = null;
                    }
                    haptic(HapticFeedbackConstants.CONFIRM);
                    showTempSub(getString(R.string.sub_switched, modeName(mode)), TEMP_SUB_MS);
                }
                renderMode();
            }
        });
        if (mode >= 0 && mode < 3 && TileStore.saveMode(this, mode)) {
            TileStore.push(this);
        }
    }

    @Override
    public void onSpatial(final Boolean state) {
        if (state == null) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (SystemClock.elapsedRealtime() - spatialSetAt < ECHO_GUARD_MS) return;
                spatialGuard = true;
                swSpatial.setChecked(state);
                spatialGuard = false;
            }
        });
    }

    @Override
    public void onDeepX(final Integer mode) {
        if (mode == null) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                deepxCurrent.setText(deepxNameFor(mode));
                if (deepxPendingTask != null) {
                    ui.removeCallbacks(deepxPendingTask);
                    deepxPendingTask = null;
                }
                if (SystemClock.elapsedRealtime() - deepxSetAt < ECHO_GUARD_MS) return;
                for (int i = 0; i < DEEPX_MODES.length; i++) {
                    if (DEEPX_MODES[i] == mode) {
                        deepxMode = i;
                        renderDeepx();
                        return;
                    }
                }
            }
        });
    }

    @Override
    public void onDisconnected(String reason) {
        connecting = false;
        ui.post(new Runnable() {
            @Override
            public void run() {
                clearData();
                setState(R.string.status_idle, COL_IDLE, true, false, lastSeenSub());
                setSub(lastSeenSub());
            }
        });
        TileStore.push(this);
        if (!isFinishing() && !isDestroyed() && resumed) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (reconnectTask != null) {
            ui.removeCallbacks(reconnectTask);
        }
        if (reconnectAttempts >= MAX_AUTO_RECONNECT) {
            showTempSub(getString(R.string.sub_reconnect_paused), 3000L);
            return;
        }
        reconnectTask = new Runnable() {
            @Override
            public void run() {
                reconnectTask = null;
                if (!isFinishing() && !isDestroyed()
                        && (client == null || !client.isConnected())) {
                    reconnectAttempts++;
                    tryConnect();
                }
            }
        };
        ui.postDelayed(reconnectTask, RECONNECT_DELAY_MS);
    }

    @Override
    public void onError(String msg) {
        connecting = false;
        Log.d(TAG, "onError: " + msg);
        ui.post(new Runnable() {
            @Override
            public void run() {
                clearData();
                setState(R.string.status_conn_failed, COL_DANGER, false, false, "");
                setChip(null);
            }
        });
    }

    // ---------------- 工具 ----------------

    private String lastSeenSub() {
        TileSnapshot snap = TileStore.readOrEmpty(this);
        if (snap.lastOk <= 0) {
            return getString(R.string.tile_last_seen, "--");
        }
        return TileState.lastSeenText(this, snap.lastOk);
    }

    private String modeName(int mode) {
        if (mode == TwsClient.MODE_OFF) return getString(R.string.btn_off);
        if (mode == TwsClient.MODE_TRANS) return getString(R.string.btn_trans);
        if (mode == TwsClient.MODE_ANC) return getString(R.string.btn_anc);
        return "--";
    }

    private String[] deepxNames() {
        String[] names = new String[DEEPX_NAME_RES.length];
        for (int i = 0; i < DEEPX_NAME_RES.length; i++) {
            names[i] = getString(DEEPX_NAME_RES[i]);
        }
        return names;
    }

    private String deepxNameAt(int index) {
        if (index < 0 || index >= DEEPX_NAME_RES.length) return "--";
        return getString(DEEPX_NAME_RES[index]);
    }

    private String deepxNameFor(int mode) {
        for (int i = 0; i < DEEPX_MODES.length; i++) {
            if (DEEPX_MODES[i] == mode) return getString(DEEPX_NAME_RES[i]);
        }
        return deepxNameAt(-1);
    }

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 在已配对设备里挑目标: 统一交给 DriverRegistry(排除手机, 优先品牌耳机 + A2DP Sink 角色) */
    static BluetoothDevice findTarget(BluetoothAdapter adapter) {
        return DriverRegistry.findTarget(adapter);
    }

    private void haptic(int kind) {
        try {
            getWindow().getDecorView().performHapticFeedback(kind);
        } catch (RuntimeException ignored) {
            // 无触觉马达或系统设置关闭时静默
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
    /** 新版=横向进度条(数字行+条), 经典=压扁圆环(数字在环里). 只切电量区, 其余 UI 相同.
     *  单电池设备统一按"单张进度卡"显示, 不套三格布局. */
    private void applyBatteryStyle() {
        boolean showCards = curUiStyle == AppPrefs.STYLE_NEW;
        boolean showRing = curUiStyle == AppPrefs.STYLE_CLASSIC;
        boolean showBlocks = curUiStyle == AppPrefs.STYLE_LIQUID;
        boolean single = singleBatt;
        if (cardL != null) cardL.setVisibility(showCards || single ? View.VISIBLE : View.GONE);
        if (cardR != null) cardR.setVisibility(showCards && !single ? View.VISIBLE : View.GONE);
        if (cardC != null) cardC.setVisibility(showCards && !single ? View.VISIBLE : View.GONE);
        if (batteryHeader != null) batteryHeader.setVisibility(showCards || single ? View.VISIBLE : View.GONE);
        if (classicBatteryCard != null) {
            classicBatteryCard.setVisibility(!showCards && !single ? View.VISIBLE : View.GONE);
        }
        if (ringRow != null) ringRow.setVisibility(showRing && !single ? View.VISIBLE : View.GONE);
        if (blockRow != null) blockRow.setVisibility(showBlocks && !single ? View.VISIBLE : View.GONE);
    }

}

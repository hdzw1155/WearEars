package com.hdz.wearcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * 主界面: 状态头 + 电量环卡 + 噪声控制卡 + 开发者设置(隐藏) + 折叠的免责声明.
 *
 * <p>交互回执约定(与磁贴共用同一批字符串):
 * 主状态行只由"连接域"决定(连接中/已连接/未连接/蓝牙未开/无权限/没找到耳机/连接失败),
 * 机型、低电量、充电、切换成功等都只改副行或小芯片, 不顶掉主行.
 */
public class MainActivity extends Activity implements TwsClient.Listener, ControlHub.UiListener, MainPanel.Actions {

    private static final int REQ_PERM = 1;
    private static final long TEMP_SUB_MS = 1600L;
    // 视觉令牌 (与 res/values/colors.xml 保持一致; 代码里要动态给自绘控件上色)
    private static final int COL_ACCENT = 0xFF2B6CB0;
    private static final int COL_DIM = 0x99F2F2F5;
    private static final int COL_OK = 0xFF4CC38A;
    private static final int COL_WARN = 0xFFF5A524;
    private static final int COL_DANGER = 0xFFF0524F;
    private static final int COL_IDLE = 0x61F2F2F5;

    private static final int SUP_ACCENT = 0xFF1E4182;
    private static final int SUP_DIM = 0xFF5E646E;
    private static final int SUP_OK = 0xFF2E7D52;
    private static final int SUP_WARN = 0xFFD97706;
    private static final int SUP_DANGER = 0xFFC9433E;
    private static final int SUP_IDLE = 0xFF8C939E;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ControlHub client;
    private MainPanels panels;
    /** 旋转表冠/触摸表圈的滚动容器(事件若没人消费会冒泡到 Activity, 由这里接住) */
    private AppScrollView scrollView;
    /** 贴屏幕右缘的弧形指示条: 独立覆盖层, 不随内容滚动 */
    private ArcIndicatorView indicator;
    private SuprematistFanDeckView fanDeck;
    private SuprematistPageRailView pageRail;
    private View supAcousticPage;
    private View supSpatialPage;
    private View supExtrasPage;
    private View supPhoneBar;
    private View supBtnPrev;
    private View supBtnNext;
    private TextView supPhonePageLabel;
    private SuprematistDialView supDial;
    private SuprematistControlView supControl;
    private boolean lastConnState = false;

    private StatusDotView dot;
    private TextView status;
    private TextView sub;
    private TextView chip;
    private View ctaReconnect;

    /** 当前界面风格/语言/形态/协同模式: onResume 里与之对比, 变了就 recreate 立即生效 */
    private int curUiStyle;
    private String curLang;
    private int curHostForm;
    private int curMeshMode;
    private boolean hudMode;
    private View hudRoot;
    private boolean supreme;
    private boolean neo;
    private boolean lightUi;

    // ---- 局域网多端本地协同状态（仅前台活动期运行，切后台 stopAll 零耗电） ----
    private String meshHostIp = "";
    private int meshHostPort = 0;
    private String meshHostPin = "";
    private int meshHostClients = 0;
    private boolean meshClientConnected = false;
    private String meshRemoteHostName = "";
    private final DeviceState remoteState = new DeviceState();

    private final SwissHudHelper.HudKeyActions hudKeyActions = new SwissHudHelper.HudKeyActions() {
        @Override public void onSetNoiseMode(int mode) {
            setMode(mode);
        }

        @Override public void onRefresh() {
            onRefreshTapped();
        }

        @Override public void onCycleNoiseMode() {
            cycleNoiseMode();
        }

        @Override public boolean onStepFocusedControl(int delta) {
            View focused = getCurrentFocus();
            if (focused instanceof NoiseSegmentedView) {
                stepNoiseSegmented(delta);
                return true;
            }
            return false;
        }
    };

    private final LocalMeshManager.HostStatusListener hostStatusListener = new LocalMeshManager.HostStatusListener() {
        @Override public void onHostStarted(String ip, int port, String pin) {
            if (!resumed || curMeshMode != LocalMeshManager.MODE_HOST) return;
            meshHostIp = ip == null ? "" : ip;
            meshHostPort = port;
            meshHostPin = pin == null ? "" : pin;
            meshHostClients = LocalMeshManager.get(MainActivity.this).clientCount();
            updateHostMeshBanner();
            ControlHub.get(MainActivity.this).pushMeshStateIfHosting();
        }

        @Override public void onHostClientCountChanged(int count) {
            if (!resumed || curMeshMode != LocalMeshManager.MODE_HOST) return;
            meshHostClients = count;
            updateHostMeshBanner();
        }

        @Override public void onHostStopped() {
            meshHostPort = 0;
            meshHostClients = 0;
        }

        @Override public void onHostError(String reason) {
            if (!resumed || curMeshMode != LocalMeshManager.MODE_HOST) return;
            showTempSub(getString(R.string.sub_mesh_error, reason == null ? "" : reason), 3000L);
        }
    };

    private final LocalMeshManager.ClientListener meshClientListener = new LocalMeshManager.ClientListener() {
        @Override public void onClientConnected(String hostName) {
            if (!resumed || curMeshMode != LocalMeshManager.MODE_CLIENT) return;
            meshClientConnected = true;
            meshRemoteHostName = hostName == null ? "" : hostName;
            setChip(getString(R.string.chip_mesh_client));
            setState(R.string.status_mesh_connected, colOk(), false, false, meshRemoteHostName);
        }

        @Override public void onClientState(LocalMeshProtocol.StateSnapshot snapshot) {
            if (!resumed || curMeshMode != LocalMeshManager.MODE_CLIENT) return;
            applyRemoteSnapshot(snapshot);
        }

        @Override public void onClientDisconnected(String reason) {
            if (!resumed || curMeshMode != LocalMeshManager.MODE_CLIENT) return;
            meshClientConnected = false;
            remoteState.disconnect();
            renderPanels();
            setChip(getString(R.string.chip_mesh_client));
            setState(R.string.status_mesh_disconnected, colIdle(), true, false,
                    reason == null ? "" : reason);
        }

        @Override public void onClientError(String reason) {
            if (!resumed || curMeshMode != LocalMeshManager.MODE_CLIENT) return;
            meshClientConnected = false;
            remoteState.disconnect();
            renderPanels();
            setChip(getString(R.string.chip_mesh_client));
            setState(R.string.status_mesh_disconnected, colWarn(), false, false,
                    getString(R.string.sub_mesh_error, reason == null ? "" : reason));
        }
    };

    private View disclaimerRow;

    private int pendingMode = -1;
    private Runnable tempSubTask;
    private boolean connecting;
    private String deviceName = "";

    private boolean disclaimerDialogUp = false;
    private boolean permPrompted = false;
    private AlertDialog disclaimerDialog, noticeDialog;

    /** 主界面是否在前台(onResume/onPause 维护; 断开后的自动重连只在界面前台时投任务) */
    private boolean resumed = false;

    // ---- 主状态行当前值, 供临时副行结束后恢复 ----
    private int stateRes = R.string.status_idle;
    private int stateColor = COL_IDLE;
    private String stateSub = "";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocalControlReceiver.setBridgeEnabled(AppPrefs.localIntentBridge(this));
        curUiStyle = AppPrefs.uiStyle(this);
        curLang = AppPrefs.lang(this);
        int formOverride = AppPrefs.hostFormOverride(this);
        curHostForm = DeviceForm.resolveForm(this, formOverride);
        curMeshMode = AppPrefs.meshMode(this);
        hudMode = (curHostForm == HostFormLogic.FORM_HUD);

        if (hudMode) {
            // 瑞士国际主义纯黑双栏 HUD 固定加载 Neo 布局（纯黑光波导底色，不走至上圆表扇形页）
            supreme = false;
            neo = true;
            lightUi = false;
            stateColor = colIdle();
            setTheme(NeoStyle.theme(AppPrefs.STYLE_NEO_NEW));
            setContentView(R.layout.activity_main_neo);
        } else {
            supreme = curUiStyle == AppPrefs.STYLE_SUPREME;
            neo = AppPrefs.isNeo(curUiStyle);
            lightUi = supreme || NeoStyle.light(curUiStyle);
            stateColor = colIdle();
            if (neo) {
                setTheme(NeoStyle.theme(curUiStyle));
            }
            setContentView(supreme ? R.layout.activity_main_supreme
                    : R.layout.activity_main_neo);
        }
        applyPhoneWindowChrome();

        scrollView = (AppScrollView) findViewById(R.id.scroll);
        indicator = (ArcIndicatorView) findViewById(R.id.indicator);
        DeviceForm.styleScrollContainer(this, scrollView, indicator, formOverride);
        if (indicator != null && scrollView != null && curHostForm == HostFormLogic.FORM_WATCH) {
            indicator.attachTo(scrollView);
        }
        fanDeck = (SuprematistFanDeckView) findViewById(R.id.fan_deck);
        pageRail = (SuprematistPageRailView) findViewById(R.id.page_rail);
        supAcousticPage = findViewById(R.id.sup_page_acoustic);
        supSpatialPage = findViewById(R.id.sup_page_spatial);
        supExtrasPage = findViewById(R.id.sup_page_extras);
        supPhoneBar = findViewById(R.id.sup_phone_bar);
        supBtnPrev = findViewById(R.id.sup_btn_prev);
        supBtnNext = findViewById(R.id.sup_btn_next);
        supPhonePageLabel = (TextView) findViewById(R.id.sup_phone_page_label);
        supDial = (SuprematistDialView) findViewById(R.id.sup_dial);
        supControl = (SuprematistControlView) findViewById(R.id.sup_control);
        if (fanDeck != null) {
            if (supAcousticPage != null) fanDeck.setPageEnabled(supAcousticPage, false);
            if (supSpatialPage != null) fanDeck.setPageEnabled(supSpatialPage, false);
            if (supExtrasPage != null) fanDeck.setPageEnabled(supExtrasPage, false);
        }
        final boolean isPhone = curHostForm == HostFormLogic.FORM_PHONE;
        if (pageRail != null && isPhone) {
            pageRail.setVisibility(View.GONE);
        }
        if (supPhoneBar != null) {
            supPhoneBar.setVisibility(isPhone ? View.VISIBLE : View.GONE);
        }
        if (isPhone && fanDeck != null) {
            if (supBtnPrev != null) {
                supBtnPrev.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                        fanDeck.stepPage(-1);
                    }
                });
            }
            if (supBtnNext != null) {
                supBtnNext.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                        fanDeck.stepPage(1);
                    }
                });
            }
        }
        if (supControl != null) {
            supControl.setLanguageEnglish(AppPrefs.LANG_EN.equals(curLang));
            supControl.setOnControlActionListener(new SuprematistControlView.OnControlActionListener() {
                @Override
                public void onOpenSettings() {
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                    startActivity(SettingsActivity.intent(MainActivity.this));
                }

                @Override
                public void onOpenSpatialLab() {
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                    startActivity(SpatialLabActivity.intent(MainActivity.this));
                }
            });
        }
        if (supDial != null) {
            supDial.setLanguageEnglish(AppPrefs.LANG_EN.equals(curLang));
            supDial.setOnHeroActionListener(new SuprematistDialView.OnHeroActionListener() {
                @Override
                public void onRefreshRequested() {
                    onRefreshTapped();
                }

                @Override
                public void onReconnectRequested() {
                    onReconnectTapped();
                }

                @Override
                public void onNoiseModeSelected(int mode) {
                    setMode(mode);
                }

                @Override
                public void onVolumeChanged(int level, int max) {
                    panels.volume(level, max);
                }

                @Override
                public void onVolumeGuardHint(boolean unlocked) {
                    showTempSub(getString(unlocked
                            ? R.string.sup_vol_unlocked_hint
                            : R.string.sup_vol_guard_hint), 2200L);
                }
            });
            supDial.setOnNoiseLongPressListener(new Runnable() {
                @Override public void run() {
                    if (fanDeck != null && supAcousticPage != null
                            && supAcousticPage.getVisibility() == View.VISIBLE) {
                        fanDeck.focusPage(supAcousticPage, true);
                    }
                }
            });
        }
        if (fanDeck != null) {
            fanDeck.setOnDeckStateListener(new SuprematistFanDeckView.OnDeckStateListener() {
                @Override
                public void onDeckMoved(int settledPage, int pageCount, float position,
                                        boolean lightSurface, boolean awake) {
                    if (pageRail != null && !isPhone) {
                        pageRail.setPageCount(pageCount);
                        pageRail.setPosition(position, lightSurface, awake);
                    }
                    if (isPhone) {
                        updatePhoneDeckBar(settledPage, pageCount);
                    }
                }
            });
            if (isPhone) {
                updatePhoneDeckBar(fanDeck.activePageIndex(), fanDeck.activePageCount());
            }
        }

        dot = (StatusDotView) findViewById(R.id.status_dot);
        if (dot != null) {
            if (lightUi) {
                dot.setDiamond(true);
                dot.setDotState(colIdle(), true);
            }
            dot.setContentDescription(getString(R.string.status_idle));
        }
        status = (TextView) findViewById(R.id.status);
        sub = (TextView) findViewById(R.id.sub);
        chip = (TextView) findViewById(R.id.status_chip);
        ctaReconnect = findViewById(R.id.cta_reconnect);

        View refreshCard = findViewById(R.id.refresh_card);
        if (refreshCard != null) {
            refreshCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    View nb = findViewById(R.id.neo_battery);
                    if (nb instanceof NeoBatteryView) ((NeoBatteryView) nb).bounce();
                    onRefreshTapped();
                }
            });
        }

        // 电量区双形态: 新版横向进度条 / 经典压扁圆环, 其余 UI 完全一致

        disclaimerRow = findViewById(R.id.disclaimer_row);

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

        panels = new MainPanels(this, this);
        renderPanels();
        wireListeners();
        if (hudMode) {
            setupSwissHudSplit();
        } else {
            applyPhoneInsets();
        }
        entryAnimation();
    }

    private void setupSwissHudSplit() {
        View colView = findViewById(R.id.neo_content_column);
        if (!(colView instanceof LinearLayout) || scrollView == null) {
            return;
        }
        ViewGroup hostRoot = scrollView.getParent() instanceof ViewGroup
                ? (ViewGroup) scrollView.getParent() : null;
        if (hostRoot == null) {
            return;
        }

        LinearLayout column = (LinearLayout) colView;
        List<View> children = new ArrayList<>(column.getChildCount());
        for (int i = 0; i < column.getChildCount(); i++) {
            children.add(column.getChildAt(i));
        }
        column.removeAllViews();

        LinearLayout statusPane = new LinearLayout(this);
        statusPane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout controlsPane = new LinearLayout(this);
        controlsPane.setOrientation(LinearLayout.VERTICAL);

        boolean movedToControls = false;
        for (View child : children) {
            if (!movedToControls) {
                statusPane.addView(child);
                if (child.getId() == R.id.neo_battery_card) {
                    movedToControls = true;
                }
            } else {
                controlsPane.addView(child);
            }
        }

        LinearLayout split = SwissHudHelper.wrapAsSwissHudSplit(this, statusPane, controlsPane);
        hostRoot.removeAllViews();
        hostRoot.setBackgroundColor(SwissHudHelper.COLOR_BG);
        hostRoot.addView(split, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scrollView = null;
        indicator = null;
        hudRoot = split;

        int[] swissCards = {
                R.id.neo_battery_card, R.id.volume_card, R.id.noise_card,
                R.id.equalizer_card, R.id.spatial_card, R.id.local_spatial_card,
                R.id.extras_toggles_card, R.id.scope_limit_card, R.id.disclaimer_row
        };
        for (int cardId : swissCards) {
            SwissHudHelper.applySwissCardStyle(split.findViewById(cardId));
        }

        View noiseSeg = split.findViewById(R.id.noise_seg);
        if (noiseSeg != null) {
            noiseSeg.setClickable(true);
            noiseSeg.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cycleNoiseMode();
                }
            });
            SwissHudHelper.applySwissFocus(this, noiseSeg);
        }

        SwissHudHelper.applyFocusTree(this, split);
        split.post(new Runnable() {
            @Override public void run() {
                SwissHudHelper.requestInitialFocus(split);
            }
        });
    }

    private void applyPhoneWindowChrome() {
        if (getResources().getConfiguration().isScreenRound()) {
            return;
        }
        int barCol = lightUi ? (neo ? 0xFFF4F2EC : 0xFFF4F3EE) : 0xFF000000;
        getWindow().setStatusBarColor(barCol);
        getWindow().setNavigationBarColor(barCol);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (lightUi) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        decor.setSystemUiVisibility(flags);
    }

    private void applyPhoneInsets() {
        int formOverride = AppPrefs.hostFormOverride(this);
        DeviceForm.styleScrollContainer(this, scrollView, indicator, formOverride);
        if (getResources().getConfiguration().isScreenRound()) {
            return;
        }
        final View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        final int phoneSide = (int) dp(24);
        final int maxColPx = curHostForm == HostFormLogic.FORM_PHONE ? DeviceForm.phoneColumnWidthPx(this) : 0;
        View neoCol = findViewById(R.id.neo_content_column);
        if (neoCol == null && scrollView != null && scrollView.getChildCount() > 0) {
            neoCol = scrollView.getChildAt(0);
        }
        final View scrollChild = neoCol;
        final int baseScrollTop = scrollChild != null ? scrollChild.getPaddingTop() : 0;
        final int baseScrollBot = scrollChild != null ? scrollChild.getPaddingBottom() : 0;
        final View supBar = supPhoneBar;
        final int baseBarMarginBot = supBar != null && supBar.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams
                ? ((android.view.ViewGroup.MarginLayoutParams) supBar.getLayoutParams()).bottomMargin : 0;
        final View rawAcousticBody = parentView(findViewById(R.id.noise_sub_container));
        final View supAcousticBody = rawAcousticBody != supAcousticPage ? rawAcousticBody : null;
        final View rawSpatialBody = parentView(findViewById(R.id.local_spatial_card));
        final View supSpatialBody = rawSpatialBody != supSpatialPage ? rawSpatialBody : null;
        final View rawExtrasBody = parentView(findViewById(R.id.extras_card));
        final View supExtrasBody = rawExtrasBody != supExtrasPage ? rawExtrasBody : null;
        final View supNoticeBox = parentView(disclaimerRow);
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
                int padR = lastInsets[3] + side;
                if (scrollChild != null) {
                    scrollChild.setPadding(padL, baseScrollTop + lastInsets[0],
                            padR, baseScrollBot + lastInsets[1]);
                }
                if (supreme) {
                    int supTop = (int) dp(20) + lastInsets[0];
                    int supBot = (int) dp(supBar != null && supBar.getVisibility() == View.VISIBLE ? 64 : 20)
                            + lastInsets[1];
                    if (supSpatialBody != null) supSpatialBody.setPadding(padL, supTop, padR, supBot);
                    if (supNoticeBox != null) supNoticeBox.setPadding(padL, supTop, padR, supBot);
                    if (supAcousticBody != null) supAcousticBody.setPadding(padL, supTop, padR, supBot);
                    if (supExtrasBody != null) supExtrasBody.setPadding(padL, supTop, padR, supBot);
                    if (supBar != null && supBar.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams lp =
                                (android.view.ViewGroup.MarginLayoutParams) supBar.getLayoutParams();
                        int targetMargin = baseBarMarginBot + lastInsets[1];
                        if (lp.bottomMargin != targetMargin) {
                            lp.bottomMargin = targetMargin;
                            supBar.setLayoutParams(lp);
                        }
                    }
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
                    if ((r - l) != (oldR - oldL)) syncPadding.run();
                }
            });
        }
        root.requestApplyInsets();
    }

    private static View parentView(View child) {
        return child != null && child.getParent() instanceof View ? (View) child.getParent() : null;
    }

    private void updatePhoneDeckBar(int settledPage, int pageCount) {
        int total = Math.max(1, pageCount);
        int current = Math.max(0, Math.min(total - 1, settledPage));
        if (supPhonePageLabel != null) {
            supPhonePageLabel.setText((current + 1) + " / " + total);
        }
        if (supBtnPrev != null) {
            boolean canPrev = current > 0;
            supBtnPrev.setEnabled(canPrev);
            supBtnPrev.setAlpha(canPrev ? 1f : 0.38f);
        }
        if (supBtnNext != null) {
            boolean canNext = current < total - 1;
            supBtnNext.setEnabled(canNext);
            supBtnNext.setAlpha(canNext ? 1f : 0.38f);
        }
    }

    private void wireListeners() {
        if (ctaReconnect != null) {
            ctaReconnect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onReconnectTapped();
                }
            });
        }

        // 免责声明已全文铺在页面底部, 点击不再弹窗
        disclaimerRow.setOnClickListener(null);
        View supFullNotice = findViewById(R.id.sup_full_notice);
        if (supFullNotice != null) {
            supFullNotice.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                    showFullNoticeInfoDialog();
                }
            });
        }

        View refreshBtn = findViewById(R.id.refresh);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    View nb = findViewById(R.id.neo_battery);
                    if (nb instanceof NeoBatteryView) ((NeoBatteryView) nb).bounce();
                    onRefreshTapped();
                }
            });
        }

        View neoBattery = findViewById(R.id.neo_battery);
        if (neoBattery != null) {
            neoBattery.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onRefreshTapped();
                }
            });
        }

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
        LocalControlReceiver.setBridgeEnabled(AppPrefs.localIntentBridge(this));
        // 设置页可能改了界面风格/语言/显示形态/多端协同模式: 回到主界面时重建一次, 立即生效
        int style = AppPrefs.uiStyle(this);
        String lang = AppPrefs.lang(this);
        int form = DeviceForm.resolveForm(this, AppPrefs.hostFormOverride(this));
        int meshMode = AppPrefs.meshMode(this);
        if (style != curUiStyle || !lang.equals(curLang) || form != curHostForm || meshMode != curMeshMode) {
            recreate();
            return;
        }
        resumed = true;
        if (scrollView != null) scrollView.setActive(true);
        if (indicator != null && curHostForm == HostFormLogic.FORM_WATCH) indicator.setActive(true);
        if (fanDeck != null) fanDeck.setActive(true);
        if (pageRail != null && curHostForm == HostFormLogic.FORM_WATCH) pageRail.wake();
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) {
            connectMeshClient();
        } else {
            tryConnect();
            if (curMeshMode == LocalMeshManager.MODE_HOST && AppPrefs.hasDisclaimer(this)) {
                startMeshHost();
            }
        }
        renderPanels();
        panels.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (scrollView != null) scrollView.setActive(false);
        if (indicator != null && curHostForm == HostFormLogic.FORM_WATCH) indicator.setActive(false);
        if (fanDeck != null) fanDeck.setActive(false);
        panels.pause();
        ui.removeCallbacksAndMessages(null);
        tempSubTask = null;
        connecting = false;
        if (dot != null) dot.setBreathing(false);
        if (curMeshMode != LocalMeshManager.MODE_OFF) {
            LocalMeshManager.get(this).stopAll();
            meshClientConnected = false;
        }
        if (client != null) {
            client.removeListener(this);
            client.disconnect();
            client = null;
        }
    }

    /**
     * 旋转表冠 / 触摸表圈: 与 SettingsActivity 保持完全一致的震动与滚动代理逻辑，
     * 直接转交给 AppScrollView 处理（内含 AXIS_SCROLL / AXIS_VSCROLL / AXIS_RELATIVE_Y 完整分发与触感反馈）。
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (fanDeck != null && fanDeck.onGenericMotionEvent(ev)) {
            return true;
        }
        AppScrollView target = activeScroll();
        return target != null && target.onGenericMotionEvent(ev) || super.onGenericMotionEvent(ev);
    }

    /**
     * 国产安卓手表的实体表冠/旋钮按键: 与 SettingsActivity 保持完全一致，统一由 AppScrollView 处理按键翻页与触感。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (hudMode && SwissHudHelper.handleHudKeyEvent(event, hudKeyActions)) {
            return true;
        }
        if (fanDeck != null && fanDeck.handleRotaryKey(keyCode)) {
            return true;
        }
        AppScrollView target = activeScroll();
        return target != null && target.handleRotaryKey(keyCode) || super.onKeyDown(keyCode, event);
    }

    // ---------------- 状态行 ----------------

    private void setState(int textRes, int color, boolean hollow, boolean breathing,
                          String subText) {
        stateRes = textRes;
        stateColor = color;

        stateSub = subText == null ? "" : subText;
        if (dot != null) {
            dot.setDotState(color, hollow);
            dot.setBreathing(breathing);
            dot.setContentDescription(getString(textRes));
            if (textRes == R.string.status_connected || textRes == R.string.status_mesh_connected) {
                dot.pop();
            }
        }
        if (status != null) {
            status.setText(textRes);
        }
        if (sub != null) {
            sub.setTextColor(colDim());
        }
        setSub(stateSub);
        boolean connectedVisual = textRes == R.string.status_connected
                || textRes == R.string.status_parked
                || textRes == R.string.status_mesh_connected
                || (textRes == R.string.status_connecting && client != null && client.isConnected());
        if (ctaReconnect != null) {
            boolean showCta = textRes != R.string.status_connecting
                    && textRes != R.string.status_mesh_connecting
                    && textRes != R.string.status_connected
                    && textRes != R.string.status_mesh_connected
                    && textRes != R.string.status_parked;
            if (ctaReconnect instanceof TextView) {
                ((TextView) ctaReconnect).setText(textRes == R.string.status_mesh_unconfigured
                        ? R.string.cta_open_settings : R.string.cta_reconnect);
            }
            ctaReconnect.setVisibility(showCta ? View.VISIBLE : View.GONE);
        }
        lastConnState = connectedVisual;
        if (supDial != null) {
            supDial.setConnectionState(getString(textRes), stateSub, color,
                    lastConnState, breathing, !lastConnState
                            && textRes != R.string.status_connecting
                            && textRes != R.string.status_mesh_connecting);
        }
        renderPanels();
    }

    private void setSub(String text) {
        String t = text == null ? "" : text;
        if (sub != null && !t.contentEquals(sub.getText())) {
            sub.setText(t);
        }
        if (supDial != null) {
            supDial.setSubText(t);
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
        if (chip != null) {
            if (text == null || text.length() == 0) {
                chip.setVisibility(View.GONE);
            } else {
                chip.setText(text);
                chip.setVisibility(View.VISIBLE);
            }
        }
        if (supDial != null) {
            supDial.setChipText(text);
        }
    }

    private void tryConnect() {
        if (!AppPrefs.hasDisclaimer(this)) {
            showDisclaimerDialog(true);
            return;
        }
        if (!hasPerm()) {
            setState(R.string.status_no_perm, colDanger(), false, false,
                    getString(R.string.sub_no_perm_action));
            if (!permPrompted) {
                permPrompted = true;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, REQ_PERM);
            }
            return;
        }
        permPrompted = false;
        if (!resumed) return;
        ControlHub shared = ControlHub.get(this);
        client = shared;
        shared.addListener(this);
        shared.connect();
    }

    private void onReconnectTapped() {
        haptic(HapticFeedbackConstants.KEYBOARD_TAP);
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) {
            if (stateRes == R.string.status_mesh_unconfigured) {
                startActivity(DeveloperSettingsActivity.intent(this));
                return;
            }
            connectMeshClient();
            return;
        }
        permPrompted = false;
        if (AppPrefs.hasDisclaimer(this) && hasPerm()
                && stateRes == R.string.status_no_perm && !hasScanPerm()) {
            permPrompted = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN}, REQ_PERM);
            return;
        }
        clearData();
        tryConnect();
    }

    @Override
    public void onConnectionState(final int state, final int hint) {
        if (!resumed) return;
        connecting = state == R.string.status_connecting;
        boolean parked = state == R.string.status_parked;
        boolean contested = state == R.string.status_channel_contested;
        int color = connecting ? colAccent()
                : parked ? colOk()
                : (state == R.string.status_bt_off || contested) ? colWarn()
                : colIdle();
        boolean hollow = !connecting && !parked && !contested;
        String subText;
        if (parked) {
            String name = (deviceName != null && !deviceName.isEmpty())
                    ? deviceName : TileStore.readOrEmpty(this).name;
            String hintText = getString(R.string.sub_parked_hint);
            subText = (name != null && !name.isEmpty()) ? name + " · " + hintText : hintText;
        } else {
            subText = hint == 0 ? TileStore.readOrEmpty(this).name : getString(hint);
        }
        setState(state, color, hollow, connecting, subText);
        showSession();
    }

    private void showSession() {
        ControlHub shared = ControlHub.get(this);
        renderPanels();
        if (!shared.isConnected()) clearData();
    }

    private void spinRefreshIcon() {
        if (!neo) return;
        View header = findViewById(R.id.neo_classic_header);
        boolean classicHeader = header != null && header.getVisibility() == View.VISIBLE;
        View icon = findViewById(classicHeader ? R.id.refresh_card : R.id.refresh);
        if (icon == null || !icon.isShown()) return;
        icon.animate().cancel();
        icon.setRotation(0f);
        icon.animate().rotation(360f).setDuration(560L)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f))
                .start();
    }

    private void onRefreshTapped() {
        haptic(HapticFeedbackConstants.CLOCK_TICK);
        spinRefreshIcon();
        panels.refreshVolume();
        if (!resumed) return;
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) {
            if (!AppPrefs.hasDisclaimer(this)) {
                showDisclaimerDialog(true);
                return;
            }
            if (meshClientConnected && LocalMeshManager.get(this).sendRefresh()) {
                showTempSub(getString(R.string.sub_refreshing), 2500L);
            } else {
                connectMeshClient();
            }
            return;
        }
        permPrompted = false;
        if (!AppPrefs.hasDisclaimer(this) || !hasPerm()) { tryConnect(); return; }
        if (stateRes == R.string.status_no_perm && !hasScanPerm()) {
            permPrompted = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN}, REQ_PERM);
            return;
        }
        // 刷新是一笔跨重连的同步请求，不能在离线时退化成只尝试 connect。
        ControlHub shared = ControlHub.get(this);
        client = shared;
        shared.addListener(this);
        shared.perform(-1);
        showTempSub(getString(R.string.sub_refreshing), 3000L);
    }

    private void setMode(int mode) {
        haptic(HapticFeedbackConstants.KEYBOARD_TAP);
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) {
            if (!meshClientConnected || !LocalMeshManager.get(this).sendNoise(mode)) {
                showTempSub(getString(R.string.sub_not_connected), TEMP_SUB_MS);
                connectMeshClient();
            } else {
                showTempSub(getString(R.string.sub_switched, modeName(mode)), TEMP_SUB_MS);
            }
            return;
        }
        if (client == null || !client.isConnected()) {
            showTempSub(getString(R.string.sub_not_connected), TEMP_SUB_MS);
            tryConnect();
            return;
        }
        client.perform(mode);
    }

    private void cycleNoiseMode() {
        DeviceState ds = activeState();
        int next = LocalControlReceiver.nextNoiseMode(ds != null ? ds.mode : -1);
        setMode(next);
    }

    private void stepNoiseSegmented(int delta) {
        int[] order = {TwsClient.MODE_OFF, TwsClient.MODE_TRANS, TwsClient.MODE_ANC};
        DeviceState ds = activeState();
        int cur = ds != null ? ds.mode : -1;
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == cur) {
                idx = i;
                break;
            }
        }
        int nextIdx = Math.max(0, Math.min(order.length - 1, idx + (delta >= 0 ? 1 : -1)));
        if (order[nextIdx] != cur) {
            setMode(order[nextIdx]);
        }
    }

    private void startMeshHost() {
        LocalMeshManager mesh = LocalMeshManager.get(this);
        mesh.syncFirewallFromPrefs(this);
        mesh.startHost(ControlHub.get(this), hostStatusListener);
        ControlHub.get(this).pushMeshStateIfHosting();
    }

    private void updateHostMeshBanner() {
        if (curMeshMode != LocalMeshManager.MODE_HOST || meshHostPort <= 0) return;
        String ip = meshHostIp.isEmpty() ? LocalMeshManager.get(this).localLanIp() : meshHostIp;
        String pin = meshHostPin.isEmpty() ? LocalMeshManager.get(this).pin() : meshHostPin;
        setChip(getString(R.string.chip_mesh_host, ip + ":" + meshHostPort, pin));
        String info = meshHostClients > 0
                ? getString(R.string.sub_mesh_host_clients, ip, meshHostPort, pin, meshHostClients)
                : getString(R.string.sub_mesh_host_brief, ip, meshHostPort, pin);
        if (tempSubTask == null && !lastConnState) {
            setSub(info);
        }
    }

    private void connectMeshClient() {
        if (!AppPrefs.hasDisclaimer(this)) {
            showDisclaimerDialog(true);
            return;
        }
        if (!resumed) return;
        String ip = AppPrefs.meshHostIp(this);
        int port = AppPrefs.meshHostPort(this);
        String pin = AppPrefs.meshPin(this);
        if (!LocalMeshProtocol.isPrivateLanAddress(ip) || !LocalMeshProtocol.isValidPin(pin)) {
            meshClientConnected = false;
            remoteState.disconnect();
            setChip(getString(R.string.chip_mesh_client));
            setState(R.string.status_mesh_unconfigured, colWarn(), false, false,
                    getString(R.string.sub_mesh_open_settings));
            return;
        }
        setChip(getString(R.string.chip_mesh_client));
        setState(R.string.status_mesh_connecting, colAccent(), false, true, ip + ":" + port);
        LocalMeshManager.get(this).connectClient(ip, port, pin, meshClientListener);
    }

    private void applyRemoteSnapshot(LocalMeshProtocol.StateSnapshot snap) {
        if (snap == null) return;
        remoteState.disconnect();
        if (snap.connected) {
            remoteState.begin("lan-mesh", snap.deviceName, snap.caps, snap.caps);
            remoteState.connected = true;
            remoteState.parked = snap.parked;
            remoteState.mode = snap.mode;
            remoteState.battery(snap.left, snap.right, snap.box, snap.charge);

            // 严禁在从机模式暴露 FIND_MY_BUDS（听觉安全红线）
            if (snap.ancLevel >= 0) {
                int maxOpt = Math.max(2, Math.min(3, snap.ancLevel));
                OptionItem[] ancOpts = new OptionItem[maxOpt + 1];
                ancOpts[0] = new OptionItem(0, "轻度", "Mild");
                ancOpts[1] = new OptionItem(1, "均衡", "Balanced");
                ancOpts[2] = new OptionItem(2, "深度", "Deep");
                if (maxOpt >= 3) ancOpts[3] = new OptionItem(3, "自适应", "Adaptive");
                remoteState.featureSpec(FeatureDescriptor.choice(
                        FeatureId.ANC_LEVEL, Controls.VERIF_REAL, FeatureDescriptor.SAFETY_NONE,
                        1 << Controls.MODE_ANC, false, ancOpts));
                remoteState.featureValue(FeatureId.ANC_LEVEL, snap.ancLevel);
            }
            if (snap.windVal >= 0) {
                remoteState.featureSpec(FeatureDescriptor.toggle(
                        FeatureId.WIND_REDUCTION, Controls.VERIF_REAL, FeatureDescriptor.SAFETY_NONE,
                        (1 << Controls.MODE_ANC) | (1 << Controls.MODE_TRANS), false));
                remoteState.featureValue(FeatureId.WIND_REDUCTION, snap.windVal);
            }
            if (snap.eqVal >= 0) {
                SoundPreset[] base = SoundPreset.vivo();
                boolean found = false;
                for (SoundPreset p : base) {
                    if (p.id == snap.eqVal) { found = true; break; }
                }
                if (found) {
                    remoteState.presets = base;
                } else {
                    SoundPreset[] ext = new SoundPreset[base.length + 1];
                    System.arraycopy(base, 0, ext, 0, base.length);
                    ext[base.length] = new SoundPreset(snap.eqVal, "预设 " + snap.eqVal, "Preset " + snap.eqVal);
                    remoteState.presets = ext;
                }
                remoteState.capabilities(remoteState.caps | Controls.CAP_EQUALIZER,
                        remoteState.potentialCaps | Controls.CAP_EQUALIZER);
                remoteState.featureValue(FeatureId.EQUALIZER, snap.eqVal);
            }
            if (snap.spatialVal >= 0) {
                remoteState.capabilities(remoteState.caps | Controls.CAP_SPATIAL,
                        remoteState.potentialCaps | Controls.CAP_SPATIAL);
                remoteState.featureSpec(FeatureDescriptor.toggle(
                        FeatureId.SPATIAL_AUDIO, Controls.VERIF_UNTESTED,
                        FeatureDescriptor.SAFETY_AUDIO_WARN, 0, false));
                remoteState.featureValue(FeatureId.SPATIAL_AUDIO, snap.spatialVal);
            }
            String label = !snap.deviceName.isEmpty() ? snap.deviceName : meshRemoteHostName;
            setChip(getString(R.string.chip_mesh_client));
            setState(snap.parked ? R.string.status_parked : R.string.status_mesh_connected,
                    colOk(), false, false, label);
        } else {
            setChip(getString(R.string.chip_mesh_client));
            setState(R.string.status_mesh_connected, colIdle(), true, false, meshRemoteHostName);
        }
        if (hudMode && hudRoot != null) {
            SwissHudHelper.applyFocusTree(this, hudRoot);
        }
    }

    private DeviceState activeState() {
        return curMeshMode == LocalMeshManager.MODE_CLIENT
                ? remoteState
                : ControlHub.get(this).state();
    }

    // ---------------- 弹窗 ----------------

    /** 全局免责: 首次/换 versionCode 必弹, 同版本不再打扰; 拒绝直接退出 */
    private void showDisclaimerDialog(boolean blockOnStart) {
        if (disclaimerDialogUp) return;
        disclaimerDialogUp = true;
        disclaimerDialog = new AlertDialog.Builder(this, NeoStyle.dialogTheme(lightUi))
                .setTitle(R.string.global_disclaimer_title)
                .setMessage(R.string.global_disclaimer_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_agree, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        AppPrefs.acceptDisclaimer(MainActivity.this);
                        TileStore.push(MainActivity.this);
                        disclaimerDialogUp = false;
                        renderPanels();
                        if (curMeshMode == LocalMeshManager.MODE_CLIENT) {
                            connectMeshClient();
                        } else {
                            tryConnect();
                            if (curMeshMode == LocalMeshManager.MODE_HOST) {
                                startMeshHost();
                            }
                        }
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

    private void showFullNoticeInfoDialog() {
        if (noticeDialog != null && noticeDialog.isShowing()) return;
        noticeDialog = new AlertDialog.Builder(this, NeoStyle.dialogTheme(lightUi))
                .setTitle(R.string.global_disclaimer_title)
                .setMessage(R.string.disclaimer_page_msg)
                .setPositiveButton(R.string.dialog_ok, null)
                .show();
    }

    @Override protected void onDestroy() {
        if (disclaimerDialog != null) disclaimerDialog.dismiss();
        if (noticeDialog != null) noticeDialog.dismiss();
        SafetyGates.dismissAll(this);
        super.onDestroy();
    }

    private void onRefreshPermissionDenied() {
        setState(R.string.status_no_perm, colDanger(), false, false,
                getString(R.string.sub_no_perm_action));
        setSub(getString(R.string.sub_no_perm_action));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERM) {
            if (hasPerm()) {
                permPrompted = false;
                if (resumed) tryConnect();
            } else {
                onRefreshPermissionDenied();
            }
        }
    }

    // ---------------- 视图渲染 ----------------

    private void syncHostSpatial(DeviceState ds) {
        boolean hwSpatialOn = ds != null && ds.connected
                && (Boolean.TRUE.equals(ds.spatial)
                        || Integer.valueOf(1).equals(ds.featureValue(FeatureId.SPATIAL_AUDIO)));
        WearEarsSpatialEffect.get(this).sync(this, hwSpatialOn);
    }

    private void renderPanels() {
        DeviceState ds = activeState();
        syncHostSpatial(ds);
        if (panels != null) panels.render(ds, pendingMode);
        if (hudMode && hudRoot != null) {
            SwissHudHelper.applyFocusTree(this, hudRoot);
        }
    }

    /** 未连接时清空电量、档位、芯片与临时提示 */
    private void clearData() {
        pendingMode = -1;
        renderPanels();
        setChip(null);
        if (tempSubTask != null) ui.removeCallbacks(tempSubTask);
        tempSubTask = null;
    }

    // ---------------- TwsClient.Listener (控制器在主线程回调) ----------------

    @Override
    public void onConnected(final String name) {
        connecting = false;
        final ControlHub c = client;
        final int verif = c != null ? c.verifLevel() : TwsClient.VERIF_UNTESTED;
        // 磁贴靠 caps 决定显示三段电量/降噪轨还是单电量; 未验证驱动给个小标签, 功能照常尝试
        deviceName = name == null ? "" : name;
        setState(R.string.status_connected, colOk(), false, false, deviceName);
        renderPanels();
        boolean untested = verif == TwsClient.VERIF_UNTESTED;
        if (curMeshMode == LocalMeshManager.MODE_HOST && meshHostPort > 0) {
            updateHostMeshBanner();
        } else {
            setChip(untested ? getString(R.string.chip_unverified) : null);
        }
        if (sub != null) {
            sub.setTextColor(untested ? colWarn() : colDim());
        }
        if (untested) {
            setSub(getString(R.string.sub_model_unverified));
        }
    }

    @Override
    public void onBattery(int left, int right, int box, int chargeFlags) {
        if (client == null) return;
        renderPanels();
        int[] levels = {left, right, box};
        int[] labels = {(client.caps() & Controls.CAP_BATTERY_SINGLE) != 0 ? R.string.label_total : R.string.label_left,
                R.string.label_right, R.string.label_case};
        int low = DeviceState.lowComponent(levels, chargeFlags);
        if (low >= 0) {
            setChip(getString(R.string.chip_low_battery));
            stateSub = getString(R.string.sub_low_battery, getString(labels[low]), levels[low]);
        } else {
            boolean experimental = client.verifLevel() == Controls.VERIF_UNTESTED;
            if (curMeshMode == LocalMeshManager.MODE_HOST && meshHostPort > 0) {
                updateHostMeshBanner();
            } else {
                setChip(experimental ? getString(R.string.chip_unverified) : null);
            }
            stateSub = experimental ? getString(R.string.sub_model_unverified) : deviceName;
        }
        if (tempSubTask == null) setSub(stateSub);
    }

    @Override
    public void onNoiseMode(int mode) {
        if (client != null) renderPanels();
    }

    @Override public void selectNoise(int mode) { setMode(mode); }
    @Override public void selectSpatial(boolean enabled) {
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) {
            if (meshClientConnected) {
                LocalMeshManager.get(this).sendFeature(FeatureId.SPATIAL_AUDIO, enabled ? 1 : 0);
            }
            return;
        }
        if (client != null && client.isConnected()) client.setSpatial(enabled);
    }
    @Override public void selectEqualizer(int preset) {
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) {
            if (meshClientConnected) {
                LocalMeshManager.get(this).sendFeature(FeatureId.EQUALIZER, preset);
            }
            return;
        }
        if (client != null && client.isConnected()) client.setEqualizer(preset);
    }
    @Override public void selectFeature(FeatureId id, int value) {
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) {
            if (id == FeatureId.FIND_MY_BUDS || !LocalMeshProtocol.isAllowedRemoteFeature(id)) {
                return;
            }
            if (meshClientConnected) {
                LocalMeshManager.get(this).sendFeature(id, value);
            }
            return;
        }
        if (client != null && client.isConnected()) client.performFeature(id, value);
    }
    @Override public void stopFindBuds() {
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) return;
        if (client != null) client.stopFindBuds();
    }
    @Override public int pendingFeature(FeatureId id) {
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) return -1;
        return client != null ? client.pendingFeature(id) : -1;
    }
    @Override public boolean isFindBudsRinging() {
        if (curMeshMode == LocalMeshManager.MODE_CLIENT) return false;
        return client != null && client.isFindBudsRinging();
    }
    @Override public void onSpatial(Boolean state) { renderPanels(); }
    @Override public void onDeepX(Integer mode) { renderPanels(); }
    @Override public void onEqualizer(Integer mode) { renderPanels(); }
    @Override public void onFeatureSpec(FeatureDescriptor spec) { if (client != null) renderPanels(); }
    @Override public void onFeatureValue(FeatureId id, int value) { if (client != null) renderPanels(); }
    @Override public void onFeatureText(FeatureId id, String text) { if (client != null) renderPanels(); }

    @Override public void onFeatureChanged(FeatureId id, int value, boolean active, boolean success) {
        if (!resumed) return;
        renderPanels();
        if (!active && !success) {
            haptic(HapticFeedbackConstants.REJECT);
            showTempSub(getString(id == FeatureId.SPATIAL_AUDIO
                    ? R.string.spatial_unconfirmed : R.string.feature_failed), TEMP_SUB_MS);
        } else if (!active && success) {
            haptic(HapticFeedbackConstants.CONFIRM);
        }
    }

    @Override public void onOperationChanged(int target, boolean active, boolean success) {
        pendingMode = active ? target : -1;
        renderPanels();
        if (!active && success && target >= 0) {
            haptic(HapticFeedbackConstants.CONFIRM);
            showTempSub(getString(R.string.sub_switched, modeName(target)), TEMP_SUB_MS);
        }
    }

    @Override
    public void onDisconnected(String reason) {
        connecting = false;
        showSession();
        setState(R.string.status_idle, colIdle(), true, false, lastSeenSub());
        setSub(lastSeenSub());
        TileStore.push(this);
    }

    @Override
    public void onError(final String msg) {
        if (!resumed) return;
        pendingMode = -1;
        renderPanels();
        showTempSub(msg, 3000L);
        haptic(HapticFeedbackConstants.REJECT);
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

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPerm() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }


    private void haptic(int kind) {
        try {
            getWindow().getDecorView().performHapticFeedback(kind);
        } catch (RuntimeException ignored) {
            // 无触觉马达或系统设置关闭时静默
        }
    }

    /** 三种旧版风格共用原来的滚动容器。 */
    private AppScrollView activeScroll() {
        return scrollView;
    }

    private int colAccent() { return lightUi ? SUP_ACCENT : COL_ACCENT; }
    private int colDim() { return lightUi ? SUP_DIM : COL_DIM; }
    private int colOk() { return lightUi ? SUP_OK : COL_OK; }
    private int colWarn() { return lightUi ? SUP_WARN : COL_WARN; }
    private int colDanger() { return lightUi ? SUP_DANGER : COL_DANGER; }
    private int colIdle() { return lightUi ? SUP_IDLE : COL_IDLE; }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}

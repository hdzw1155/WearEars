package com.hdz.wearcontrol;

import com.hdz.wearcontrol.vivo.VivoGaiaClient;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/** vivo 耳机 App 风格: 卡片式深色布局 + 三选一圆形模式按钮 + 三段电量条 */
public class MainActivity extends Activity implements VivoGaiaClient.Listener {

    private static final int REQ_PERM = 1;

    private static final int COL_SEL = Color.rgb(0x34, 0x78, 0xF6);   // vivo 蓝
    private static final int COL_LABEL_GRAY = Color.rgb(0x8E, 0x8E, 0x93);
    private static final int COL_ICON_GRAY = Color.rgb(0x9A, 0x9A, 0x9E);
    private static final int COL_GREEN = Color.rgb(0x34, 0xC7, 0x59); // 电量绿
    private static final int COL_RED = Color.rgb(0xFA, 0x51, 0x51);   // 低电量红
    private static final int COL_TRACK = Color.rgb(0x3A, 0x3A, 0x3C); // 电量槽灰

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TwsClient client;
    private TextView status;
    private TextView sub;
    private ImageView imgOff;
    private ImageView imgTrans;
    private ImageView imgAnc;
    private TextView lblOff;
    private TextView lblTrans;
    private TextView lblAnc;
    private TextView pctL;
    private TextView pctR;
    private TextView pctC;
    private ProgressBar barL;
    private ProgressBar barR;
    private ProgressBar barC;
    private int lastMode = -1;
    private long connectStartAt = 0;
    private Runnable refresher;
    private Switch swSpatial;
    private boolean spatialGuard;
    private long spatialSetAt = 0;
    // DeepX 音效: 真实模式值 0/1/2/3/5(0x04 被官方跳过,悠扬听书=5),官方协议文档实锤
    private static final String[] DEEPX_NAMES =
            {"标准", "清澈人声", "超重低音", "清亮高音", "悠扬听书"};
    private static final int[] DEEPX_MODES = {0, 1, 2, 3, 5};

    /** DeepX 模式值 → 显示名(查表,未知值返回 未知) */
    private static String deepxNameFor(int mode) {
        for (int i = 0; i < DEEPX_MODES.length; i++) {
            if (DEEPX_MODES[i] == mode) return DEEPX_NAMES[i];
        }
        return "未知";
    }

    // 空间音效预设(推断值 0..4): 标准/剧场/音乐厅/录音厅/演唱会
    private static final String[] SPATIALFX_NAMES =
            {"标准", "剧场", "音乐厅", "录音棚", "演唱会"};

    private Button btnDeepx;
    private int deepxMode = -1;   // 当前档位(数组下标)
    private long deepxSetAt = 0;
    private View devSection;
    private TextView devHint;
    private int devTaps = 0;
    private long lastTapAt = 0;
    private boolean disclaimerOk = false;
    private boolean disclaimerDialogUp = false;
    private Runnable reconnector;
    private long reconnectDelay = 3000L;
    private Runnable bgExit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        sub = findViewById(R.id.sub);
        imgOff = findViewById(R.id.img_off);
        imgTrans = findViewById(R.id.img_trans);
        imgAnc = findViewById(R.id.img_anc);
        lblOff = findViewById(R.id.lbl_off);
        lblTrans = findViewById(R.id.lbl_trans);
        lblAnc = findViewById(R.id.lbl_anc);
        pctL = findViewById(R.id.pct_l);
        pctR = findViewById(R.id.pct_r);
        pctC = findViewById(R.id.pct_c);
        barL = findViewById(R.id.bar_l);
        barR = findViewById(R.id.bar_r);
        barC = findViewById(R.id.bar_c);
        View refresh = findViewById(R.id.btn_refresh);
        swSpatial = findViewById(R.id.switch_spatial);
        btnDeepx = findViewById(R.id.btn_deepx);
        devSection = findViewById(R.id.dev_section);

        // 开发者设置: 默认关闭,连点入口 5 下本会话内显示(不持久化);
        // 实验功能目前只有 vivo 实现, 其他品牌直接藏入口
        devHint = findViewById(R.id.dev_hint);
        devHint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long now = SystemClock.elapsedRealtime();
                devTaps = (now - lastTapAt > 2000) ? 1 : devTaps + 1;
                lastTapAt = now;
                if (devTaps >= 5 && devSection.getVisibility() != View.VISIBLE) {
                    devSection.setVisibility(View.VISIBLE);
                    Toast.makeText(MainActivity.this, R.string.dev_unlocked_toast,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        swSpatial.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (spatialGuard) return;
                spatialSetAt = SystemClock.elapsedRealtime();
                // 首次启用弹个别化风险确认,记住后不再打扰(履行个别警示义务)
                if (isChecked && !getSharedPreferences("cfg", MODE_PRIVATE)
                        .getBoolean("spatial_ack", false)) {
                    spatialGuard = true;
                    swSpatial.setChecked(false);
                    spatialGuard = false;
                    showSpatialDialog();
                    return;
                }
                Toast.makeText(MainActivity.this, R.string.spatial_toast, Toast.LENGTH_SHORT).show();
                if (client instanceof VivoGaiaClient && client.isConnected()) {
                    ((VivoGaiaClient) client).setSpatial(isChecked);
                } else {
                    Toast.makeText(MainActivity.this, "未连接", Toast.LENGTH_SHORT).show();
                }
            }
        });
        // DeepX 五档循环按钮(真实值 0/1/2/3/5); 只切音效不伤耳,不加确认弹窗
        btnDeepx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (client == null || !client.isConnected()) {
                    Toast.makeText(MainActivity.this, "未连接", Toast.LENGTH_SHORT).show();
                    return;
                }
                deepxMode = (deepxMode + 1) % DEEPX_NAMES.length;
                deepxSetAt = SystemClock.elapsedRealtime();
                btnDeepx.setText(DEEPX_NAMES[deepxMode]);
                if (client instanceof VivoGaiaClient) {
                    ((VivoGaiaClient) client).setDeepX(DEEPX_MODES[deepxMode]);
                }
            }
        });

        findViewById(R.id.mode_off).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(TwsClient.MODE_OFF); }
        });
        findViewById(R.id.mode_trans).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(TwsClient.MODE_TRANS); }
        });
        findViewById(R.id.mode_anc).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(TwsClient.MODE_ANC); }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (client != null && client.isConnected()) {
                    client.queryBattery();
                    client.queryNoise();
                } else {
                    tryConnect();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryConnect();
        // 阶梯轮询省电: 连上先快查两轮拿首屏,之后转 30 秒慢轮询;
        // 空间音频/DeepX 状态几乎不变,降到每 4 轮(约 2 分钟)问一次
        if (refresher == null) {
            refresher = new Runnable() {
                int tick = 0;

                @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    tick++;
                    if (client != null && client.isConnected()) {
                        client.queryBattery();
                        client.queryNoise();
                        if (client instanceof VivoGaiaClient
                                && (tick == 1 || tick % 4 == 0)) {
                            ((VivoGaiaClient) client).querySpatial();
                            ((VivoGaiaClient) client).queryDeepX();
                        }
                    } else if (client == null
                            || SystemClock.elapsedRealtime() - connectStartAt > 12000) {
                        // 没有进行中的连接尝试才重连,避免打断慢连接
                        tryConnect();
                    }
                    // 前 2 轮 5 秒(首屏),之后 30 秒(省电)
                    long delay = tick <= 2 ? 5000L : 30000L;
                    ui.postDelayed(this, delay);
                }
            };
        }
        ui.removeCallbacks(refresher);
        ui.postDelayed(refresher, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 手表应用切后台就断开,省电;挂着的重连任务一并取消,别在后台偷偷连
        ui.removeCallbacks(refresher);
        if (reconnector != null) ui.removeCallbacks(reconnector);
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 回到前台,撤销后台自杀倒计时
        if (bgExit != null) ui.removeCallbacks(bgExit);
    }

    /** 表冠/旋转表圈滚动页面: ScrollView 自己能滚就不管, 没滚到底时兜底直喂 */
    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_SCROLL) {
            float scroll = ev.getAxisValue(android.view.MotionEvent.AXIS_SCROLL);
            if (scroll != 0f) {
                android.widget.ScrollView sv = findViewById(R.id.scroll_root);
                if (sv != null) {
                    sv.smoothScrollBy(0, (int) (scroll * 60));
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(ev);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 后台15秒自动退场: 空进程赖在后台也不省电,磁贴/跳板都是系统按需拉起的;
        // 跳板正在连耳机时先让路,等它收工再退。
        // 注意: 只 finish 不 killProcess——硬杀会留下未关闭的 RFCOMM 句柄,
        // 耳机侧连接位被"尸体"占着,下次打开疯狂连断循环表现为卡死
        if (bgExit == null) {
            bgExit = new Runnable() {
                @Override public void run() {
                    if (ModeCyclerActivity.running) {
                        ui.postDelayed(this, 3000);
                        return;
                    }
                    finishAndRemoveTask();
                }
            };
        }
        ui.removeCallbacks(bgExit);
        ui.postDelayed(bgExit, 15000);
    }

    /** 首次启用空间音频的个别化风险确认框,确认后记住(spatial_ack) */
    private void showSpatialDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.spatial_dialog_title)
                .setMessage(R.string.spatial_dialog_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_ok, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                                .putBoolean("spatial_ack", true).apply();
                        spatialSetAt = SystemClock.elapsedRealtime();
                        if (client instanceof VivoGaiaClient && client.isConnected()) {
                            ((VivoGaiaClient) client).setSpatial(true);
                        }
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** 全局免责确认框: 同意后记录"同意时的版本号",
     *  升级新版本或卸载重装会重新弹出,同一版本连续使用不再打扰 */
    private void showDisclaimerDialog() {
        if (disclaimerDialogUp) return;
        disclaimerDialogUp = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.global_disclaimer_title)
                .setMessage(R.string.global_disclaimer_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_agree, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                                .putInt("disclaimer_ack_ver", currentVersionCode()).apply();
                        disclaimerOk = true;
                        disclaimerDialogUp = false;
                        tryConnect();
                    }
                })
                .setNegativeButton(R.string.dialog_refuse, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        disclaimerDialogUp = false;
                        finish();
                    }
                })
                .show();
    }

    private int currentVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 在已配对设备里挑目标: 名字能被工厂认出的优先,再兜底任意音频设备 */
    static BluetoothDevice findTarget(BluetoothAdapter adapter) {
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return null;
        for (BluetoothDevice d : bonded) {
            if (ClientFactory.create(d) != null) return d;
        }
        for (BluetoothDevice d : bonded) {
            ParcelUuid[] uuids = d.getUuids();
            if (uuids == null) continue;
            for (ParcelUuid u : uuids) {
                if (u.getUuid().equals(VivoGaiaClient.A2DP_SINK_UUID)) return d;
            }
        }
        return null;
    }

    private void tryConnect() {
        // 全局免责确认: 每个新版本首次使用都要重新同意,同版本连续使用不再弹
        if (!disclaimerOk && getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("disclaimer_ack_ver", -1) != currentVersionCode()) {
            showDisclaimerDialog();
            return;
        }
        if (!hasPerm()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
            return;
        }
        if (client != null && client.isConnected()) return;
        // 灭掉残留的旧连接(含还在握手的线程),防止新旧两个客户端打架
        if (client != null) {
            client.disconnect();
            client = null;
        }

        BluetoothAdapter adapter =
                ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            clearData();
            status.setText("请先打开手表蓝牙");
            sub.setText("");
            return;
        }
        BluetoothDevice target = findTarget(adapter);
        if (target == null) {
            clearData();
            status.setText("未找到已配对的耳机");
            sub.setText("请先在 手表设置-蓝牙 配对");
            return;
        }
        TwsClient c = ClientFactory.create(this, target);
        if (c == null) {
            // 名字不认识但像音频设备, 按 vivo 协议试一把
            c = new VivoGaiaClient();
        }
        c.addListener(this);
        client = c;
        status.setText(R.string.status_connecting);
        sub.setTextColor(COL_LABEL_GRAY);
        sub.setText(target.getName());
        // 记下 MAC 给磁贴做实时连接状态判断
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("tile_mac", target.getAddress()).apply();
        connectStartAt = SystemClock.elapsedRealtime();
        client.connect(target);
    }

    private void setMode(int mode) {
        if (client == null || !client.isConnected()) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show();
            tryConnect();
            return;
        }
        client.setNoiseMode(mode);
    }

    private void applyMode(ImageView img, TextView lbl, boolean sel) {
        img.setBackgroundResource(sel ? R.drawable.circle_sel : R.drawable.circle_norm);
        img.setImageTintList(ColorStateList.valueOf(sel ? Color.WHITE : COL_ICON_GRAY));
        lbl.setTextColor(sel ? COL_SEL : COL_LABEL_GRAY);
    }

    private void renderMode() {
        ui.post(new Runnable() {
            @Override public void run() {
                applyMode(imgOff, lblOff, lastMode == TwsClient.MODE_OFF);
                applyMode(imgTrans, lblTrans, lastMode == TwsClient.MODE_TRANS);
                applyMode(imgAnc, lblAnc, lastMode == TwsClient.MODE_ANC);
            }
        });
    }

    /** 电量未知时耳机上报 0xFF,显示 -- */
    private void setBatt(final TextView pct, final ProgressBar bar,
                         final int v, final boolean charging) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (v >= 0 && v <= 100) {
                    pct.setText(charging ? (v + "%⚡") : (v + "%"));
                    bar.setProgress(v);
                    bar.setProgressTintList(
                            ColorStateList.valueOf(v <= 20 ? COL_RED : COL_GREEN));
                } else {
                    pct.setText("--");
                    bar.setProgress(0);
                }
                bar.setProgressBackgroundTintList(ColorStateList.valueOf(COL_TRACK));
            }
        });
    }

    // ---- GaiaClient.Listener (后台线程回调) ----

    @Override
    public void onConnected(final String deviceName) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("tile_status", "已连接")
                .putString("tile_name", deviceName == null ? "耳机" : deviceName)
                .putLong("tile_last_ok", System.currentTimeMillis())
                .apply();
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(Color.WHITE);
                status.setText("已连接");
                sub.setTextColor(COL_LABEL_GRAY);
                boolean vivo = client instanceof VivoGaiaClient;
                // 华为/小米是实验性支持,未在任何真机上验证过,明说
                sub.setText(deviceName + (vivo ? "" : "（实验性支持·未验证）"));
                // 实验功能仅 vivo 有, 其他品牌藏掉开发者入口
                devHint.setVisibility(vivo ? View.VISIBLE : View.GONE);
                if (!vivo) devSection.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onBattery(final int left, final int right, final int box, final int chargeFlags) {
        setBatt(pctL, barL, left, (chargeFlags & 0x01) != 0);
        setBatt(pctR, barR, right, (chargeFlags & 0x02) != 0);
        setBatt(pctC, barC, box, (chargeFlags & 0x04) != 0);
        // 给系统磁贴留快照
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putInt("tile_batt_l", left)
                .putInt("tile_batt_r", right)
                .putInt("tile_batt_c", box)
                .apply();
        refreshTiles();
    }

    @Override
    public void onNoiseMode(int mode) {
        lastMode = mode;
        renderMode();
        if (mode >= 0 && mode < 3) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putInt("tile_mode", mode)
                    .putString("tile_mode_name",
                            new String[]{"降噪开", "降噪关", "通透"}[mode])
                    .apply();
        }
        refreshTiles();
    }

    /** 数据变了就叫系统立刻重画磁贴 */
    private void refreshTiles() {
        try {
            androidx.wear.tiles.TileService.getUpdater(this)
                    .requestUpdate(TwsTileService.class);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSpatial(final Boolean state) {
        if (state == null) return;
        ui.post(new Runnable() {
            @Override public void run() {
                // 手动设置后 4 秒内不回写,避免查询结果盖掉用户刚点的状态
                if (SystemClock.elapsedRealtime() - spatialSetAt < 4000) return;
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
            @Override public void run() {
                // 手动设置后 4 秒内不回写,避免查询结果盖掉用户刚点的状态
                if (SystemClock.elapsedRealtime() - deepxSetAt < 4000) return;
                String name = deepxNameFor(mode);
                btnDeepx.setText(name);
                if (!name.equals("未知")) {
                    // 反查下标,让下次点击从当前档继续循环
                    for (int i = 0; i < DEEPX_MODES.length; i++) {
                        if (DEEPX_MODES[i] == mode) { deepxMode = i; break; }
                    }
                }
            }
        });
    }

    /** 未连接时清空电量显示与模式选中态 */
    private void clearData() {
        setBatt(pctL, barL, -1, false);
        setBatt(pctR, barR, -1, false);
        setBatt(pctC, barC, -1, false);
        lastMode = -1;
        renderMode();
        deepxMode = -1;
        ui.post(new Runnable() {
            @Override public void run() {
                spatialGuard = true;
                swSpatial.setChecked(false);
                spatialGuard = false;
                btnDeepx.setText("--");
            }
        });
    }

    @Override
    public void onDisconnected(String reason) {
        clearData();
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(COL_LABEL_GRAY);
                status.setText(R.string.status_idle);
                sub.setText("");
            }
        });
        // 耳机断连后自动重连,指数退避省电: 3s/6s/12s/24s,封顶 30s(切后台会被取消)
        if (!isFinishing() && !isDestroyed()) {
            if (reconnector != null) ui.removeCallbacks(reconnector);
            reconnector = new Runnable() {
                @Override public void run() {
                    if (!isFinishing() && !isDestroyed()
                            && (client == null || !client.isConnected())) {
                        reconnectDelay = Math.min(reconnectDelay * 2, 30000L);
                        tryConnect();
                        ui.postDelayed(this, reconnectDelay);
                    } else {
                        reconnectDelay = 3000L; // 连上了,下次断连从头退避
                    }
                }
            };
            reconnectDelay = 3000L;
            ui.postDelayed(reconnector, reconnectDelay);
        }
    }

    @Override
    public void onError(final String msg) {
        clearData();
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(COL_LABEL_GRAY);
                status.setText(msg);
                sub.setText("");
            }
        });
    }
}

package com.hdz.wearcontrol;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

/**
 * 磁贴模式切换的后台服务: 跳板 Activity 秒退, 这里无界面地连耳机切模式,
 * 完成或 8 秒超时就自杀。耳机不在时最多静默等 8 秒, 不冻结界面。
 *
 * <p>多品牌: 目标设备与驱动都由 DriverRegistry 决定; 只有驱动声明 CAP_NOISE 才真的发切档指令,
 * 其余驱动连上把电量/能力位记进磁贴就完事。切档成功/失败都只写 prefs, 由 TileStore.push 叫系统重画。
 */
public class ModeCyclerService extends Service implements TwsClient.Listener {

    private static final long TIMEOUT_MS = 8000L;

    private TwsClient client;
    private int nextMode;
    private int prevMode = TwsClient.MODE_OFF;
    private boolean done = false;
    /** 磁贴跳板已经做了乐观更新(先显示新档位), 失败/超时需要回滚 */
    private boolean optimistic = false;
    /** 耳机真的回执了新模式, 不回滚 */
    private boolean confirmed = false;
    private boolean reverted = false;
    /** 连点磁贴会把服务再拉起来一次, 用代数防止上一把的迟到回调改坏状态 */
    private int session = 0;

    private final Handler h = new Handler();

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            complete();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 没授权就点磁贴: 服务自己弹不了权限框, 把主界面拉起来让用户授权
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                Intent ui = new Intent(this, MainActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(ui);
            } catch (Throwable ignored) {
                // 拉不起来也算了, 至少别崩
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        // 上次记下的档位; 从没连过时按"关闭"起算, 保持老版本的点按顺序不变
        int last = TileStore.read(this).mode;
        if (last < TwsClient.MODE_ANC || last > TwsClient.MODE_TRANS) {
            last = TwsClient.MODE_OFF;
        }
        // 磁贴传来的指定档位(点谁切谁, 0..2); 没传或越界就循环下一档
        int wanted = intent != null ? intent.getIntExtra("target_mode", -1) : -1;
        nextMode = (wanted >= 0 && wanted <= 2) ? wanted : (last + 1) % 3;

        // 乐观更新: 跳板 Activity 已先把新档位写进磁贴并 push; 这里接住 prev/optimistic 做兜底与回滚
        prevMode = intent != null ? intent.getIntExtra("prev_mode", last) : last;
        if (prevMode < TwsClient.MODE_ANC || prevMode > TwsClient.MODE_TRANS) {
            prevMode = last;
        }
        optimistic = intent != null && intent.getBooleanExtra("optimistic", false);
        confirmed = false;
        reverted = false;
        if (!optimistic && nextMode != prevMode && TileStore.read(this).mode == prevMode) {
            // 没有跳板(或旧版本磁贴)直接拉起服务: 这里补一次乐观显示, 保证按下即亮
            optimistic = true;
        }
        if (optimistic && nextMode != prevMode && TileStore.read(this).mode != nextMode) {
            TileStore.saveMode(this, nextMode);
            TileStore.push(this);
        }

        // 磁贴连点第二下会再进这里: 灭掉上一把没收工的连接, 别让两个客户端抢耳机
        done = false;
        session++;
        dropClient();

        BluetoothAdapter adapter =
                ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        BluetoothDevice target = adapter != null ? DriverRegistry.findTarget(adapter) : null;
        if (target == null) {
            complete();
            return START_NOT_STICKY;
        }
        TwsClient c = DriverRegistry.create(this, target);
        if (c == null) {
            complete();
            return START_NOT_STICKY;
        }
        c.addListener(this);
        client = c;
        c.connect(target);
        h.postDelayed(timeout, TIMEOUT_MS);
        return START_NOT_STICKY;
    }

    /** 收工: 清掉超时、回滚未确认的乐观更新、断开连接、自杀。幂等 */
    private void complete() {
        if (done) return;
        done = true;
        h.removeCallbacksAndMessages(null);
        rollbackOptimistic();
        dropClient();
        stopSelf();
    }

    /**
     * 乐观更新兜底: 连接失败/超时/耳机没有降噪能力时, 如果磁贴还停在刚点的新档位
     * (说明期间用户没再点别的档), 就退回真实档位, 避免"显示降噪其实没切过去"。
     */
    private void rollbackOptimistic() {
        if (!optimistic || confirmed || reverted) return;
        reverted = true;
        if (nextMode == prevMode) return;
        if (TileStore.read(this).mode != nextMode) return;
        TileStore.saveMode(this, prevMode);
        TileStore.push(this);
    }

    /** 先摘监听再断连, 免得 disconnect 里回调进来又改状态 */
    private void dropClient() {
        if (client != null) {
            client.removeListener(this);
            client.disconnect();
            client = null;
        }
    }

    // ---------------- TwsClient.Listener (后台线程回调) ----------------

    @Override
    public void onConnected(String deviceName) {
        if (done || client == null) return;
        TileStore.noteOk(this);
        TileStore.saveName(this, deviceName);
        TileStore.saveCaps(this, client.caps(), client.verifLevel());
        TileStore.push(this);
        // 耳机不支持降噪就把"切档"降级成一次同步: 连上刷完电量就收工
        if ((client.caps() & TwsClient.CAP_NOISE) == 0) {
            complete();
            return;
        }
        client.setNoiseMode(nextMode);
    }

    @Override
    public void onBattery(int left, int right, int box, int chargeFlags) {
        if (done || client == null) return;
        boolean changed;
        if ((client.caps() & TwsClient.CAP_BATTERY_SINGLE) != 0) {
            changed = TileStore.saveBatterySingle(this, left);
        } else {
            changed = TileStore.saveBattery(this, left, right, box, chargeFlags);
        }
        boolean minuteChanged = TileStore.noteOk(this);
        if (changed || minuteChanged) {
            TileStore.push(this);
        }
    }

    @Override
    public void onNoiseMode(int mode) {
        // 耳机确认了新模式, 记下来让磁贴和主界面显示正确
        if (done) return;
        if (mode < 0 || mode > 2 || mode != nextMode) return;
        confirmed = true;
        boolean changed = TileStore.saveMode(this, mode);
        TileStore.noteOk(this);
        if (changed) {
            TileStore.push(this);
        }
        complete();
    }

    @Override
    public void onSpatial(Boolean state) {
    }

    @Override
    public void onDeepX(Integer mode) {
    }

    @Override
    public void onError(String msg) {
        complete();
    }

    @Override
    public void onDisconnected(String reason) {
        complete();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        dropClient();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

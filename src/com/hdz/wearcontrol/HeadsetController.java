package com.hdz.wearcontrol;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主页与磁贴共用的连接控制器。保留 TwsClient 接口，让旧 UI 只替换数据来源。
 * 状态与订阅都在主线程处理，协议写入在工作线程执行；每次连接都有独立代数。
 */
final class HeadsetController implements TwsClient {
    interface UiListener { void onConnectionState(int status, int hint); }
    interface Completion { void onComplete(boolean success); }
    private interface Command { void run(TwsClient client); }
    private static final long TIMEOUT = 12000L, POLL = 60000L;
    private static HeadsetController instance;
    static HeadsetController get(Context ctx) {
        if (instance == null) instance = new HeadsetController(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> observers = new ArrayList<Listener>();
    private volatile TwsClient driver;
    private volatile int generation, operationId;
    private Listener sessionListener;
    private boolean connecting, connected, receiverOn, operationActive, gotBattery, confirmed, operationSent;
    private int retries, targetMode = -1;
    private Boolean spatial;
    private Integer deepx;
    private Completion completion;
    private Runnable deadline, retry, poll, initialQuery, operationTimeout, settle;

    private HeadsetController(Context ctx) { app = ctx; }
    @Override public void init(Context ctx) { }
    @Override public boolean isConnected() { return connected; }
    @Override public int caps() { return driver != null ? driver.caps() : TileStore.readOrEmpty(app).caps; }
    @Override public int verifLevel() { return driver != null ? driver.verifLevel() : TileStore.readOrEmpty(app).verifLevel; }
    @Override public String brandName() { return driver != null ? driver.brandName() : ""; }
    @Override public void addListener(Listener l) { if (l != null && !observers.contains(l)) observers.add(l); }
    @Override public void removeListener(Listener l) { observers.remove(l); }
    private List<Listener> observers() { return new ArrayList<Listener>(observers); }

    /** 显式进入页面或手动重连会重置预算；轮询不会调用 connect。 */
    @Override public void connect(BluetoothDevice ignored) {
        retries = 0; cancel(retry); retry = null;
        ensureReceiver();
        if (connected) { replay(); queryAll(); schedulePoll(); }
        else ensureConnected();
    }

    @Override public void disconnect() {
        if (!observers.isEmpty()) return;
        cancel(poll); poll = null;
        cancel(retry); retry = null;
        if (operationActive && completion == null) finishOperation(false);
        releaseIfIdle();
    }

    void deviceChanged() {
        if (operationActive) finishOperation(false);
        drop(); releaseIfIdle();
    }

    /** 最新点击覆盖上一目标；乐观高亮即时推送，确认数据单独保存。 */
    void perform(int mode, Completion callback) {
        cancel(operationTimeout); cancel(settle);
        retries = 0; completion = callback; operationActive = true;
        targetMode = mode >= 0 && mode <= 2 ? mode : -1;
        gotBattery = false; confirmed = false; operationSent = false;
        final int id = ++operationId;
        ensureReceiver(); ensureConnected();
        if (!operationActive) return;
        TileStore.pending(app, targetMode);
        operationTimeout = new Runnable() { @Override public void run() {
            if (id == operationId) finishOperation(false);
        }};
        main.postDelayed(operationTimeout, TIMEOUT);
        if (connected) sendOperation();
    }
    void cancelOperation(Completion owner) {
        if (operationActive && completion == owner) finishOperation(false);
    }

    private boolean needed() { return !observers.isEmpty() || operationActive; }
    private void ensureConnected() {
        if (connected || connecting || !needed()) return;
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            unavailable(R.string.status_no_perm, R.string.sub_no_perm_action); return;
        }
        try {
            BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                unavailable(R.string.status_bt_off, R.string.sub_bt_off_action); return;
            }
            BluetoothDevice device = DriverRegistry.findTarget(app, adapter);
            if (device == null) {
                unavailable(R.string.status_no_device, R.string.sub_no_device_action); return;
            }
            drop();
            final TwsClient source = DriverRegistry.create(app, device);
            final int session = generation;
            driver = source; connecting = true; spatial = null; deepx = null;
            TileStore.selectDevice(app, device.getAddress(), device.getName(), source.caps(), source.verifLevel());
            TileStore.push(app);
            sessionListener = listener(source, session);
            source.addListener(sessionListener);
            status(R.string.status_connecting, 0);
            deadline = new Runnable() { @Override public void run() {
                if (current(source, session)) failed("连接超时");
            }};
            main.postDelayed(deadline, TIMEOUT);
            source.connect(device);
        } catch (SecurityException e) { unavailable(R.string.status_no_perm, R.string.sub_no_perm_action); }
        catch (RuntimeException e) { failed(e.toString()); }
    }

    private Listener listener(final TwsClient source, final int session) {
        return new Listener() {
            private void deliver(final Runnable task) {
                main.post(new Runnable() { @Override public void run() {
                    if (current(source, session)) task.run();
                }});
            }
            @Override public void onConnected(final String name) {
                deliver(new Runnable() { @Override public void run() {
                    connecting = false; connected = true; cancel(deadline); deadline = null;
                    TileStore.saveName(app, name);
                    TileStore.saveCaps(app, source.caps(), source.verifLevel());
                    for (Listener l : observers()) l.onConnected(name);
                    emitSnapshot();
                    if (operationActive) sendOperation();
                    initialQuery = new Runnable() { @Override public void run() {
                        if (current(source, session)) { queryAll(); querySpatial(); queryDeepX(); }
                    }};
                    main.postDelayed(initialQuery, 1800L);
                    schedulePoll(); TileStore.push(app);
                }});
            }
            @Override public void onBattery(final int left, final int right, final int box, final int flags) {
                deliver(new Runnable() { @Override public void run() {
                    boolean single = (source.caps() & CAP_BATTERY_SINGLE) != 0;
                    if (!TileSnapshot.batteryKnown(left) && (single
                            || (!TileSnapshot.batteryKnown(right) && !TileSnapshot.batteryKnown(box)))) return;
                    boolean dirty = single ? TileStore.saveBatterySingle(app, left)
                            : TileStore.saveBattery(app, left, right, box, flags);
                    dirty |= TileStore.saveCaps(app, source.caps(), source.verifLevel());
                    dirty |= TileStore.noteOk(app);
                    gotBattery = true;
                    emitBattery();
                    if (dirty) TileStore.push(app);
                    if (operationActive && (targetMode < 0 || confirmed))
                        finishOperation(true);
                }});
            }
            @Override public void onNoiseMode(final int mode) {
                deliver(new Runnable() { @Override public void run() {
                    if (mode < -1 || mode > 2) return;
                    if (mode < 0 || mode != targetMode) {
                        confirmed = false;
                        cancel(settle); settle = null;
                    }
                    boolean dirty = TileStore.saveMode(app, mode);
                    dirty |= TileStore.saveCaps(app, source.caps(), source.verifLevel());
                    dirty |= TileStore.noteOk(app);
                    for (Listener l : observers()) l.onNoiseMode(mode);
                    if (dirty) TileStore.push(app);
                    if (operationActive && targetMode >= 0 && targetMode != mode && !operationSent
                            && (source.caps() & CAP_NOISE) != 0) sendOperation();
                    if (operationActive && targetMode >= 0 && targetMode == mode) {
                        confirmed = true;
                        if (gotBattery) finishOperation(true);
                        else {
                            cancel(settle); final int id = operationId;
                            settle = new Runnable() { @Override public void run() {
                                if (id == operationId && operationActive) finishOperation(true);
                            }};
                            main.postDelayed(settle, 700L);
                        }
                    }
                }});
            }
            @Override public void onSpatial(final Boolean value) {
                deliver(new Runnable() { @Override public void run() {
                    if (TileStore.saveCaps(app, source.caps(), source.verifLevel())) TileStore.push(app);
                    spatial = value; for (Listener l : observers()) l.onSpatial(value);
                }});
            }
            @Override public void onDeepX(final Integer value) {
                deliver(new Runnable() { @Override public void run() {
                    if (TileStore.saveCaps(app, source.caps(), source.verifLevel())) TileStore.push(app);
                    deepx = value; for (Listener l : observers()) l.onDeepX(value);
                }});
            }
            @Override public void onDisconnected(final String reason) {
                deliver(new Runnable() { @Override public void run() { failed(reason); }});
            }
            @Override public void onError(final String error) {
                deliver(new Runnable() { @Override public void run() {
                    Log.d("Headset", error);
                    if (connecting || !source.isConnected()) failed(error);
                    else if (operationActive) finishOperation(false);
                    else for (Listener l : observers()) l.onError(error);
                }});
            }
        };
    }

    private void replay() {
        for (Listener l : observers()) l.onConnected(TileStore.readOrEmpty(app).name);
        emitSnapshot();
        for (Listener l : observers()) { l.onSpatial(spatial); l.onDeepX(deepx); }
    }
    private void emitSnapshot() {
        emitBattery();
        int mode = TileStore.readOrEmpty(app).confirmedMode;
        for (Listener l : observers()) l.onNoiseMode(mode);
    }
    private void emitBattery() {
        TileSnapshot s = TileStore.readOrEmpty(app);
        for (Listener l : observers()) l.onBattery(s.isSingleBattery() ? s.battTotal : s.battL,
                s.battR, s.battC, s.chargeFlags < 0 ? 0 : s.chargeFlags);
    }

    private void sendOperation() {
        if (!connected || !operationActive) return;
        final int id = operationId, mode = targetMode;
        final boolean noise = (caps() & CAP_NOISE) != 0;
        final boolean write = mode >= 0 && noise && !operationSent;
        if (write) operationSent = true;
        command(new Command() { @Override public void run(TwsClient c) {
            if (id != operationId) return;
            if (write) c.setNoiseMode(mode);
            c.queryBattery();
            if (!write) c.queryNoise();
        }});
    }
    private void finishOperation(boolean success) {
        if (!operationActive) return;
        operationActive = false; ++operationId;
        cancel(operationTimeout); operationTimeout = null; cancel(settle); settle = null;
        TileStore.pending(app, -1);
        Completion callback = completion; completion = null;
        if (!success) for (Listener l : observers()) l.onError(AppLocale.localizedContext(app)
                .getString(targetMode < 0 ? R.string.status_refresh_timeout : R.string.status_mode_failed));
        if (callback != null) callback.onComplete(success);
        releaseIfIdle();
    }

    private boolean current(TwsClient source, int id) { return driver == source && generation == id; }
    private void command(final Command command) {
        final TwsClient source = driver; final int id = generation;
        io.execute(new Runnable() { @Override public void run() {
            if (source == null || !current(source, id)) return;
            try { command.run(source); }
            catch (final RuntimeException e) { main.post(new Runnable() { @Override public void run() {
                if (current(source, id)) failed(e.toString());
            }}); }
        }});
    }
    @Override public void queryBattery() { command(new Command() { @Override public void run(TwsClient c) { c.queryBattery(); }}); }
    // 能力尚未确认时仍允许只读查询，避免丢失首包后永久隐藏本来支持的功能。
    @Override public void queryNoise() { command(new Command() { @Override public void run(TwsClient c) { c.queryNoise(); }}); }
    @Override public void setNoiseMode(int mode) { perform(mode, null); }
    @Override public void querySpatial() { command(new Command() { @Override public void run(TwsClient c) { c.querySpatial(); }}); }
    @Override public void setSpatial(final boolean value) { command(new Command() { @Override public void run(TwsClient c) { c.setSpatial(value); }}); }
    @Override public void queryDeepX() { command(new Command() { @Override public void run(TwsClient c) { c.queryDeepX(); }}); }
    @Override public void setDeepX(final int value) { command(new Command() { @Override public void run(TwsClient c) { c.setDeepX(value); }}); }
    private void queryAll() { if (connected) { queryBattery(); queryNoise(); } }

    private void schedulePoll() {
        cancel(poll); poll = null;
        if (observers.isEmpty() || !connected) return;
        poll = new Runnable() { @Override public void run() {
            if (!observers.isEmpty() && connected) { queryAll(); schedulePoll(); }
        }};
        main.postDelayed(poll, POLL);
    }
    private void status(int status, int hint) {
        for (Listener l : observers()) if (l instanceof UiListener) ((UiListener) l).onConnectionState(status, hint);
    }
    private void unavailable(int status, int hint) {
        drop();
        if (operationActive) finishOperation(false);
        status(status, hint); releaseIfIdle();
    }
    private void failed(String reason) {
        Log.d("Headset", reason); drop();
        for (Listener l : observers()) l.onDisconnected(reason);
        if (operationActive) finishOperation(false);
        if (!observers.isEmpty()) {
            if (retries >= 3) status(R.string.status_idle, R.string.sub_reconnect_paused);
            else {
                long delay = 3000L << retries++;
                retry = new Runnable() { @Override public void run() { retry = null; ensureConnected(); }};
                main.postDelayed(retry, delay);
            }
        }
        releaseIfIdle();
    }
    private void drop() {
        ++generation; connecting = false; connected = false;
        cancel(deadline); deadline = null; cancel(initialQuery); initialQuery = null;
        cancel(poll); poll = null; cancel(retry); retry = null;
        TwsClient old = driver; driver = null;
        if (old != null) { old.removeListener(sessionListener); old.disconnect(); }
        sessionListener = null;
    }
    private void releaseIfIdle() {
        if (needed()) return;
        drop();
        if (receiverOn) { app.unregisterReceiver(bluetooth); receiverOn = false; }
    }
    private void cancel(Runnable task) { if (task != null) main.removeCallbacks(task); }
    private final BroadcastReceiver bluetooth = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF)
                unavailable(R.string.status_bt_off, R.string.sub_bt_off_action);
            else if (state == BluetoothAdapter.STATE_ON && needed()) connect(null);
        }
    };
    private void ensureReceiver() {
        if (receiverOn) return;
        app.registerReceiver(bluetooth, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        receiverOn = true;
    }
}

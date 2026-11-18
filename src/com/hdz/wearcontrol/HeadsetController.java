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
    interface UiListener {
        void onConnectionState(int status, int hint);
        void onOperationChanged(int target, boolean active, boolean success);
    }
    interface Completion { void onComplete(boolean success); }
    private interface Command { void run(TwsClient client); }
    private static final long TIMEOUT = 12000L, POLL = 60000L;
    private static volatile HeadsetController instance;
    static synchronized HeadsetController get(Context ctx) {
        if (instance == null) instance = new HeadsetController(ctx.getApplicationContext());
        return instance;
    }
    static boolean hasActiveLink(String address) {
        HeadsetController controller = instance;
        if (controller == null || address == null) return false;
        if (Build.VERSION.SDK_INT >= 31 && controller.app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return false;
        try {
            BluetoothManager manager = (BluetoothManager) controller.app.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) return false;
        } catch (SecurityException revoked) { return false; }
        return controller != null && controller.connected && address.equals(controller.state.address)
                && controller.driver != null && !(controller.driver instanceof StandardClient) && controller.driver.isConnected();
    }

    private final Context app;
    private final DeviceState state = new DeviceState();
    DeviceState state() { return state; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> observers = new ArrayList<Listener>();
    private volatile TwsClient driver;
    private volatile int generation, operationId, writtenOperationId = -1;
    private Listener sessionListener;
    private boolean selecting, connecting, connected, receiverOn, operationActive, gotBattery, gotNoise, confirmed, operationSent;
    private boolean refreshRecovered;
    private int retries, rememberedNoise, targetMode = -1;
    private String operationAddress;
    private static final Completion TILE_COMPLETION = new Completion() {
        @Override public void onComplete(boolean success) { }
    };
    private Completion completion;
    private Runnable deadline, retry, poll, initialQuery, operationTimeout, settle;
    private Runnable spatialReadback, equalizerReadback;

    private HeadsetController(Context ctx) { app = ctx; }
    @Override public void init(Context ctx) { }
    @Override public boolean isConnected() { return connected; }
    @Override public int caps() { return state.caps; }
    @Override public int potentialCaps() { return state.potentialCaps; }
    @Override public int verifLevel() { return driver != null ? driver.verifLevel() : TileStore.readOrEmpty(app).verifLevel; }
    @Override public String brandName() { return driver != null ? driver.brandName() : ""; }
    @Override public void addListener(Listener l) { if (l != null && !observers.contains(l)) observers.add(l); }
    @Override public void removeListener(Listener l) { observers.remove(l); }
    private List<Listener> observers() { return new ArrayList<Listener>(observers); }

    /** 显式进入页面或手动重连会重置预算；轮询不会调用 connect。 */
    @Override public void connect(BluetoothDevice ignored) {
        retries = 0; cancel(retry); retry = null;
        ensureReceiver();
        if (connected && (driver == null || !driver.isConnected())) drop();
        if (connected) { replay(); queryAll(); queryEffects(); schedulePoll(); }
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

    void deviceDisconnected(String address) {
        if (address.equals(state.address) && (connected || connecting)) failed("耳机已断开");
    }

    /** 最新点击覆盖上一目标；乐观高亮即时推送，确认数据单独保存。 */
    void perform(int mode, Completion callback) {
        perform(mode, callback, null);
    }

    void performFromTile(final int mode, final String address) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() { @Override public void run() { performFromTile(mode, address); } });
            return;
        }
        if (address == null || address.length() == 0) return;
        // 旧磁贴不能控制切换后正在主页使用的另一副耳机。
        if ((connected || connecting) && !address.equals(state.address)) return;
        perform(mode, TILE_COMPLETION, address);
    }

    private void perform(int mode, Completion callback, String address) {
        cancel(operationTimeout); cancel(settle);
        cancel(retry); retry = null;
        Completion previous = completion;
        completion = null;
        if (previous != null) previous.onComplete(false);
        retries = 0; completion = callback; operationActive = true;
        operationAddress = address;
        targetMode = mode >= 0 && mode <= 2 ? mode : -1;
        gotBattery = gotNoise = false; confirmed = false; operationSent = false;
        refreshRecovered = false;
        final int id = ++operationId;
        if (connected && (driver == null || !driver.isConnected())) drop();
        ensureReceiver(); ensureConnected();
        if (!operationActive) return;
        TileStore.pending(app, targetMode);
        operationChanged(true, false);
        operationTimeout = new Runnable() { @Override public void run() {
            if (id != operationId || !operationActive) return;
            // 连接对象可能仍称已连，但耳机已换过连接；显式刷新允许一次重建。
            if (targetMode < 0 && !refreshRecovered) {
                refreshRecovered = true;
                if (connected && driver != null && driver.isConnected() && (gotBattery || gotNoise)) {
                    if (!gotBattery) queryBattery();
                    if (!gotNoise) queryNoise();
                } else {
                    gotBattery = gotNoise = false;
                    drop();
                    ensureConnected();
                }
                if (id == operationId && operationActive) main.postDelayed(this, TIMEOUT);
            } else finishOperation(false);
        }};
        main.postDelayed(operationTimeout, TIMEOUT);
        if (connected) sendOperation();
    }
    void cancelOperation(Completion owner) {
        if (operationActive && completion == owner) finishOperation(false);
    }

    private boolean needed() { return !observers.isEmpty() || operationActive; }
    private void ensureConnected() {
        if (connected || connecting || selecting || !needed()) return;
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
            if (operationActive && operationAddress != null) {
                startConnection(DeviceSelector.findAddress(adapter, operationAddress));
                return;
            }
            selecting = true;
            final int request = generation;
            final BluetoothAdapter sourceAdapter = adapter;
            DeviceSelector.findTarget(app, adapter, new DeviceSelector.Callback() {
                @Override public void onResult(BluetoothDevice device) {
                    if (request != generation || !needed()) return;
                    selecting = false;
                    try {
                        if (operationActive && operationAddress != null)
                            device = DeviceSelector.findAddress(sourceAdapter, operationAddress);
                        startConnection(device);
                    } catch (SecurityException revoked) { unavailable(R.string.status_no_perm, R.string.sub_no_perm_action); }
                }
            });
        } catch (SecurityException e) { unavailable(R.string.status_no_perm, R.string.sub_no_perm_action); }
        catch (RuntimeException e) { failed(e.toString()); }
    }

    private void startConnection(BluetoothDevice device) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                unavailable(R.string.status_no_perm, R.string.sub_no_perm_action); return;
            }
            if (device == null) {
                unavailable(R.string.status_no_device, R.string.sub_no_device_action); return;
            }
            drop();
            final TwsClient source = DriverRegistry.create(app, device);
            final int session = generation;
            driver = source; connecting = true;
            rememberedNoise = source instanceof StandardClient && device.getAddress().equals(TileStore.deviceAddress(app))
                    ? TileStore.readOrEmpty(app).caps & CAP_NOISE : 0;
            state.begin(device.getAddress(), device.getName(), source.caps(), potential(source));
            syncCapabilities(source);
            TileStore.selectDevice(app, device.getAddress(), device.getName(), source.caps(), potential(source), source.verifLevel());
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
                    connecting = false; connected = true; state.connected = true;
                    if (name != null && !name.isEmpty()) state.name = name;
                    syncCapabilities(source);
                    cancel(deadline); deadline = null;
                    TileStore.saveName(app, name);
                    TileStore.saveCaps(app, source.caps(), potential(source), source.verifLevel());
                    for (Listener l : observers()) l.onConnected(name);
                    emitSnapshot();
                    if (operationActive) sendOperation();
                    initialQuery = new Runnable() { @Override public void run() {
                        if (current(source, session)) { queryAll(); queryEffects(); }
                    }};
                    main.postDelayed(initialQuery, 1800L);
                    schedulePoll(); TileStore.push(app);
                }});
            }
            @Override public void onBattery(final int left, final int right, final int box, final int flags) {
                deliver(new Runnable() { @Override public void run() {
                    syncCapabilities(source);
                    state.battery(left, right, box, flags);
                    boolean single = (source.caps() & CAP_BATTERY_SINGLE) != 0;
                    if (!TileSnapshot.batteryKnown(left) && (single
                            || (!TileSnapshot.batteryKnown(right) && !TileSnapshot.batteryKnown(box)))) return;
                    boolean dirty = single ? TileStore.saveBatterySingle(app, left)
                            : TileStore.saveBattery(app, left, right, box, flags);
                    dirty |= TileStore.saveCaps(app, source.caps(), potential(source), source.verifLevel());
                    dirty |= TileStore.noteOk(app);
                    gotBattery = true;
                    emitBattery();
                    if (dirty) TileStore.push(app);
                    if (operationActive && (targetMode < 0 ? refreshReady(source) : confirmed))
                        finishOperation(true);
                }});
            }
            @Override public void onNoiseMode(final int mode) {
                deliver(new Runnable() { @Override public void run() {
                    if (mode < -1 || mode > 2) return;
                    syncCapabilities(source);
                    state.mode = mode;
                    gotNoise = mode >= 0;
                    if (mode < 0 || mode != targetMode) {
                        confirmed = false;
                        cancel(settle); settle = null;
                    }
                    boolean dirty = TileStore.saveMode(app, mode);
                    dirty |= TileStore.saveCaps(app, source.caps(), potential(source), source.verifLevel());
                    if (mode >= 0) dirty |= TileStore.noteOk(app);
                    for (Listener l : observers()) l.onNoiseMode(mode);
                    if (dirty) TileStore.push(app);
                    if (operationActive && targetMode < 0 && refreshReady(source)) finishOperation(true);
                    if (operationActive && targetMode >= 0 && targetMode != mode && !operationSent
                            && (source.caps() & CAP_NOISE) != 0) sendOperation();
                    if (operationActive && writtenOperationId == operationId && targetMode >= 0 && targetMode == mode) {
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
                    if (TileStore.saveCaps(app, source.caps(), potential(source), source.verifLevel())) TileStore.push(app);
                    syncCapabilities(source);
                    state.spatial = value;
                    ++state.spatialRevision;
                    for (Listener l : observers()) l.onSpatial(value);
                }});
            }
            @Override public void onDeepX(final Integer value) {
                deliver(new Runnable() { @Override public void run() {
                    if (TileStore.saveCaps(app, source.caps(), potential(source), source.verifLevel())) TileStore.push(app);
                    syncCapabilities(source);
                    state.equalizer = value;
                    ++state.equalizerRevision;
                    for (Listener l : observers()) l.onDeepX(value);
                }});
            }
            @Override public void onEqualizer(final Integer value) {
                deliver(new Runnable() { @Override public void run() {
                    syncCapabilities(source);
                    state.equalizer = value;
                    ++state.equalizerRevision;
                    for (Listener l : observers()) l.onEqualizer(value);
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

    private int potential(TwsClient source) { return source.potentialCaps() | rememberedNoise; }

    private void syncCapabilities(TwsClient source) {
        state.capabilities(source.caps(), potential(source));
        state.presets = source.equalizerPresets();
    }

    private void replay() {
        for (Listener l : observers()) l.onConnected(state.name);
        emitSnapshot();
        for (Listener l : observers()) { l.onSpatial(state.spatial); l.onEqualizer(state.equalizer); }
        if (operationActive) operationChanged(true, false);
    }
    private void emitSnapshot() {
        emitBattery();
        int mode = state.mode;
        for (Listener l : observers()) l.onNoiseMode(mode);
    }
    private void emitBattery() {
        for (Listener l : observers()) l.onBattery(state.left, state.right, state.box, state.charge);
    }

    private void sendOperation() {
        if (!connected || !operationActive) return;
        final int id = operationId, mode = targetMode;
        final boolean noise = (caps() & CAP_NOISE) != 0;
        final boolean write = mode >= 0 && noise && !operationSent;
        if (write) operationSent = true;
        command(new Command() { @Override public void run(TwsClient c) {
            if (id != operationId) return;
            if (write) {
                writtenOperationId = id;
                c.setNoiseMode(mode);
            }
            c.queryBattery();
            if (!write) c.queryNoise();
            if (mode < 0) {
                int features = c.potentialCaps() | c.caps();
                if ((features & CAP_SPATIAL) != 0) c.querySpatial();
                if ((features & (CAP_EQUALIZER | CAP_DEEPX)) != 0) c.queryEqualizer();
            }
        }});
    }
    private boolean refreshReady(TwsClient source) {
        int features = source.caps() | source.potentialCaps();
        return (gotBattery || gotNoise) && (gotBattery || (features & (CAP_BATTERY | CAP_BATTERY_SINGLE)) == 0)
                && (gotNoise || (features & CAP_NOISE) == 0);
    }
    private void finishOperation(boolean success) {
        if (!operationActive) return;
        operationActive = false; ++operationId;
        operationAddress = null;
        cancel(operationTimeout); operationTimeout = null; cancel(settle); settle = null;
        TileStore.pending(app, -1);
        Completion callback = completion; completion = null;
        operationChanged(false, success);
        if (!success) for (Listener l : observers()) l.onError(AppLocale.localizedContext(app)
                .getString(targetMode < 0 ? R.string.status_refresh_timeout : R.string.status_mode_failed));
        if (callback != null) callback.onComplete(success);
        releaseIfIdle();
    }

    private void operationChanged(boolean active, boolean success) {
        for (Listener listener : observers()) if (listener instanceof UiListener)
            ((UiListener) listener).onOperationChanged(targetMode, active, success);
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
    @Override public void setSpatial(final boolean value) {
        if (state.capability(CAP_SPATIAL) != DeviceState.SUPPORTED) return;
        command(new Command() { @Override public void run(TwsClient c) { c.setSpatial(value); }});
        readBack(true);
    }
    @Override public void queryDeepX() { command(new Command() { @Override public void run(TwsClient c) { c.queryDeepX(); }}); }
    @Override public void setDeepX(final int value) { setEqualizer(value); }
    @Override public SoundPreset[] equalizerPresets() { return state.presets.clone(); }
    @Override public void queryEqualizer() { command(new Command() { @Override public void run(TwsClient c) { c.queryEqualizer(); }}); }
    @Override public void setEqualizer(final int value) {
        if (!connected || (state.caps & (CAP_EQUALIZER | CAP_DEEPX)) == 0) return;
        boolean allowed = false;
        for (SoundPreset preset : state.presets) if (preset.id == value) allowed = true;
        if (!allowed) return;
        command(new Command() { @Override public void run(TwsClient c) { c.setEqualizer(value); }});
        readBack(false);
    }
    private void readBack(final boolean spatial) {
        final TwsClient source = driver;
        final int session = generation;
        cancel(spatial ? spatialReadback : equalizerReadback);
        Runnable task = new Runnable() { @Override public void run() {
            if (current(source, session) && connected) {
                if (spatial) querySpatial(); else queryEqualizer();
            }
        }};
        if (spatial) spatialReadback = task; else equalizerReadback = task;
        main.postDelayed(task, 600L);
    }
    private void queryAll() {
        if (!connected) return;
        queryBattery(); queryNoise();
    }
    private void queryEffects() {
        if (!connected) return;
        if ((state.potentialCaps & CAP_SPATIAL) != 0) querySpatial();
        if ((state.potentialCaps & (CAP_EQUALIZER | CAP_DEEPX)) != 0) queryEqualizer();
    }

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
        ++generation; selecting = false; connecting = false; connected = false;
        state.disconnect();
        TileStore.push(app);
        cancel(deadline); deadline = null; cancel(initialQuery); initialQuery = null;
        cancel(spatialReadback); spatialReadback = null;
        cancel(equalizerReadback); equalizerReadback = null;
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
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    try { deviceDisconnected(device.getAddress()); } catch (SecurityException revoked) { }
                }
                return;
            }
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF)
                unavailable(R.string.status_bt_off, R.string.sub_bt_off_action);
            else if (state == BluetoothAdapter.STATE_ON && needed()) connect(null);
        }
    };
    private void ensureReceiver() {
        if (receiverOn) return;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        app.registerReceiver(bluetooth, filter);
        receiverOn = true;
    }
}

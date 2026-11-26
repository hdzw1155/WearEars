package com.hdz.wearcontrol;

import android.bluetooth.*;
import android.content.*;
import android.os.*;
import java.util.function.Consumer;

/** Main-thread owner of selection, retry budget and physical-session lifetime. */
final class ConnectionOwner {
    interface Host {
        boolean needed();
        boolean foreground();
        String expectedAddress();
        Controls.Listener bind(TwsClient source, String address, String name, boolean wakingFromPark);
        void parked();
        default boolean canSoftParkOnDrop() { return false; }
        void reset();
        void unavailable(int status, int hint);
    }
    private enum Phase { IDLE, SELECTING, CONNECTING, LIVE, PARKED }
    private final Context app;
    private final Host host;
    private final Handler main;
    private volatile TwsClient source;
    private Controls.Listener reports;
    private volatile Phase phase = Phase.IDLE;
    private int generation, retries, shortDrops;
    private boolean listening, wakingFromPark;
    private long liveSinceMs, connectingSinceMs;
    private volatile String address = "";
    // Up to six RFCOMM candidates, followed by authentication, GATT setup or a protocol handshake.
    private static final long CONNECT_TIMEOUT_MS = 40000;
    /** 连接至少稳定存活 12 秒才清零重试计数，防止同品牌手机系统服务秒踢导致的无限闪断死循环。 */
    private static final long STABLE_SESSION_MS = 12000;
    private final Runnable retry = this::ensure;
    private final Runnable deadline = () -> failed("Connection timed out");
    private final Runnable markStable = () -> {
        if (phase == Phase.LIVE) {
            retries = 0;
            shortDrops = 0;
        }
    };
    private final BroadcastReceiver radio = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_ON) { retries = 0; shortDrops = 0; main.removeCallbacks(retry); ensure(); }
            else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF)
                stopWith(R.string.status_bt_off, R.string.sub_bt_off_action);
        }
    };
    ConnectionOwner(Context app, Handler main, Host host) { this.app = app; this.main = main; this.host = host; }
    void manual() {
        if (phase == Phase.IDLE || phase == Phase.PARKED) {
            retries = 0;
            shortDrops = 0;
        }
        main.removeCallbacks(retry);
        if (phase == Phase.PARKED) {
            wakingFromPark = true;
            phase = Phase.IDLE;
        }
        ensure();
    }
    void wakeFromPark() {
        if (phase == Phase.PARKED) {
            retries = 0;
            wakingFromPark = true;
            phase = Phase.IDLE;
            main.removeCallbacks(retry);
            ensure();
        } else {
            ensure();
        }
    }
    boolean isParked() { return phase == Phase.PARKED; }
    boolean current(TwsClient client) { return client != null && source == client; }
    boolean live() { TwsClient s = source; return phase == Phase.LIVE && s != null && s.isConnected(); }
    boolean privateLink(String target) {
        TwsClient s = source;
        return s != null && phase == Phase.LIVE && s.privateLink() && s.isConnected()
                && target.equals(address) && current(s);
    }
    boolean activePrivateLink() {
        TwsClient s = source;
        return s != null && phase == Phase.LIVE && s.privateLink() && s.isConnected();
    }
    String address() { return address; }
    boolean allowed() { return BluetoothAccess.connect(app) && AppPrefs.hasDisclaimer(app); }
    void ensure() {
        if (!host.needed()) return;
        if (!AppPrefs.hasDisclaimer(app)) { stopWith(R.string.status_idle, R.string.tile_disclaimer_required); return; }
        if (!BluetoothAccess.connect(app)) { stopWith(R.string.status_no_perm, R.string.sub_no_perm_action); return; }
        if (phase == Phase.LIVE && !live()) reset();
        if (phase != Phase.IDLE) return;
        try {
            if (!listening) {
                IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
                // Socket/GATT callbacks belong to a session. A queued ACL broadcast for the same
                // address can outlive it; the standard driver independently observes system profiles.
                try {
                    if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(radio, f, Context.RECEIVER_EXPORTED);
                    else app.registerReceiver(radio, f);
                    listening = true;
                } catch (RuntimeException ignored) { }
            }
            BluetoothManager manager = app.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { stopWith(R.string.status_bt_off, R.string.sub_bt_off_action); return; }
            phase = Phase.SELECTING;
            final int id = ++generation;
            DeviceSelector.select(app, adapter, AppPrefs.deviceAddress(app), (result, peer) -> {
                if (id != generation || phase != Phase.SELECTING) return;
                if (!AppPrefs.hasDisclaimer(app)) { stopWith(R.string.status_idle, R.string.tile_disclaimer_required); return; }
                if (!BluetoothAccess.connect(app)) { stopWith(R.string.status_no_perm, R.string.sub_no_perm_action); return; }
                switch (result) {
                    case AMBIGUOUS: stopWith(R.string.status_multiple_devices, R.string.sub_select_device_action); return;
                    case INCOMPLETE:
                        if (host.foreground() && retries < 3) {
                            stopWith(R.string.status_profiles_pending, R.string.sub_reconnecting_hint);
                            main.postDelayed(retry, 3000L << retries++);
                        } else {
                            stopWith(R.string.status_profiles_pending, R.string.sub_profiles_retry);
                        }
                        return;
                    case PIN_UNAVAILABLE: stopWith(R.string.status_pin_unavailable, R.string.sub_select_device_action); return;
                    case NONE: stopWith(R.string.status_no_device, R.string.sub_connect_audio); return;
                    default: open(peer);
                }
            });
        } catch (SecurityException revoked) { stopWith(R.string.status_no_perm, R.string.sub_no_perm_action); }
        catch (RuntimeException error) { failed(error.toString()); }
    }
    private void open(BluetoothDevice peer) {
        try {
            String nextAddress = peer.getAddress();
            boolean sameAddress = nextAddress != null && nextAddress.equalsIgnoreCase(address);
            boolean waking = wakingFromPark && sameAddress;
            if (wakingFromPark && !sameAddress) {
                // 唤醒期间自动切到了另一台耳机：先彻底清空旧设备事务，严禁串发指令
                host.reset();
            }
            wakingFromPark = false;
            address = nextAddress == null ? "" : nextAddress;
            String expected = host.expectedAddress();
            if (expected != null && !expected.equalsIgnoreCase(address)) {
                stopWith(R.string.status_pin_unavailable, R.string.sub_select_device_action); return;
            }
            source = DriverRegistry.create(app, peer);
            if (source.needsDiscoveryPermission() && !BluetoothAccess.cancelDiscovery(app)) {
                stopWith(R.string.status_no_perm, R.string.sub_no_perm_action); return;
            }
            phase = Phase.CONNECTING;
            connectingSinceMs = SystemClock.elapsedRealtime();
            reports = host.bind(source, address, peer.getName(), waking);
            source.addListener(reports);
            main.postDelayed(deadline, CONNECT_TIMEOUT_MS);
            source.connect(peer);
        } catch (SecurityException revoked) { stopWith(R.string.status_no_perm, R.string.sub_no_perm_action); }
        catch (RuntimeException error) { failed(error.toString()); }
    }
    void connected() {
        phase = Phase.LIVE;
        liveSinceMs = SystemClock.elapsedRealtime();
        main.removeCallbacks(deadline);
        main.removeCallbacks(markStable);
        main.postDelayed(markStable, STABLE_SESSION_MS);
    }
    /** 手机按需短连接模式：优雅归还 RFCOMM 通道给同品牌系统服务，保留界面与设备状态快照。 */
    void park() {
        if (phase != Phase.LIVE || !activePrivateLink()) return;
        enterParkState();
    }
    private void enterParkState() {
        ++generation;
        main.removeCallbacks(deadline);
        main.removeCallbacks(retry);
        main.removeCallbacks(markStable);
        retries = 0;
        shortDrops = 0;
        liveSinceMs = 0L;
        connectingSinceMs = 0L;
        wakingFromPark = false;
        TwsClient old = source;
        source = null;
        Controls.Listener oldReports = reports;
        reports = null;
        phase = Phase.PARKED;
        if (old != null) {
            old.removeListener(oldReports);
            old.disconnect();
        }
        host.parked();
    }
    boolean refreshPublicProfiles(Consumer<Boolean> completed) {
        TwsClient s = source;
        if (s == null || !live()) return false;
        return s.refreshPublicProfiles(found -> { if (current(s)) completed.accept(found && allowed()); });
    }
    void command(Consumer<TwsClient> action) { command(action, null); }
    void command(Consumer<TwsClient> action, Runnable rejected) {
        TwsClient s = source;
        if (!live() || !allowed()) {
            if (rejected != null) rejected.run();
            return;
        }
        Runnable work = () -> {
            if (current(s) && s.isConnected() && allowed()) {
                action.accept(s);
            } else if (rejected != null) {
                main.post(rejected);
            }
        };
        boolean queued = s instanceof WireSession ? ((WireSession) s).tryExecute(work) : runDirect(s, work);
        if (!queued && rejected != null) rejected.run();
    }
    private static boolean runDirect(TwsClient s, Runnable work) { s.execute(work); return true; }
    void failed(String reason) {
        android.util.Log.d("WearEars", reason);
        // 手机按需短连接模式：如果本轮已读回有效数据且无在途操作，被同品牌系统服务踢断时直接吸收为待命态，彻底消除闪烁与重连风暴。
        if (phase == Phase.LIVE && host.canSoftParkOnDrop()) {
            enterParkState();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean wasShortLive = phase == Phase.LIVE
                && liveSinceMs > 0L
                && (now - liveSinceMs) < STABLE_SESSION_MS;
        boolean wasShortConnect = phase == Phase.CONNECTING
                && connectingSinceMs > 0L
                && !"Connection timed out".equals(reason)
                && (now - connectingSinceMs) < STABLE_SESSION_MS;
        boolean channelContested = false;
        if (wasShortLive || wasShortConnect) {
            shortDrops++;
            if (shortDrops >= 2) {
                channelContested = true;
            }
        }
        wakingFromPark = false;
        if (channelContested) {
            stopWith(R.string.status_channel_contested, R.string.sub_channel_contested);
            return;
        }
        stopWith(R.string.status_conn_failed, R.string.sub_reconnecting_hint);
        if (host.foreground() && retries < 3) main.postDelayed(retry, 3000L << retries++);
        else if (host.foreground()) host.unavailable(R.string.status_idle, R.string.sub_reconnect_paused);
    }
    private void stopWith(int status, int hint) {
        reset();
        host.unavailable(status, hint);
        releaseIfIdle();
    }
    void reset() {
        ++generation;
        main.removeCallbacks(deadline);
        main.removeCallbacks(retry);
        main.removeCallbacks(markStable);
        liveSinceMs = 0L;
        connectingSinceMs = 0L;
        wakingFromPark = false;
        TwsClient old = source;
        source = null;
        phase = Phase.IDLE;
        address = "";
        if (old != null) { old.removeListener(reports); old.disconnect(); }
        reports = null;
        host.reset();
    }
    void releaseIfIdle() {
        if (host.needed()) return;
        if (phase != Phase.IDLE) reset();
        main.removeCallbacks(retry);
        if (listening) {
            try { app.unregisterReceiver(radio); }
            catch (RuntimeException ignored) { }
            listening = false;
        }
    }
}

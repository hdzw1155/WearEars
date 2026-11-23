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
        Controls.Listener bind(TwsClient source, String address, String name);
        void reset();
        void unavailable(int status, int hint);
    }
    private enum Phase { IDLE, SELECTING, CONNECTING, LIVE }
    private final Context app;
    private final Host host;
    private final Handler main;
    private volatile TwsClient source;
    private Controls.Listener reports;
    private volatile Phase phase = Phase.IDLE;
    private int generation, retries;
    private boolean listening;
    private volatile String address = "";
    // Up to six RFCOMM candidates, followed by authentication, GATT setup or a protocol handshake.
    private static final long CONNECT_TIMEOUT_MS = 40000;
    private final Runnable retry = this::ensure;
    private final Runnable deadline = () -> failed("Connection timed out");
    private final BroadcastReceiver radio = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_ON) ensure();
            else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF)
                stopWith(R.string.status_bt_off, R.string.sub_bt_off_action);
        }
    };
    ConnectionOwner(Context app, Handler main, Host host) { this.app = app; this.main = main; this.host = host; }
    void manual() { retries = 0; main.removeCallbacks(retry); ensure(); }
    boolean current(TwsClient client) { return client != null && source == client; }
    boolean live() { TwsClient s = source; return phase == Phase.LIVE && s != null && s.isConnected(); }
    boolean privateLink(String target) {
        TwsClient s = source;
        return s != null && phase == Phase.LIVE && s.privateLink() && s.isConnected()
                && target.equals(address) && current(s);
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
            BluetoothManager manager = app.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { stopWith(R.string.status_bt_off, R.string.sub_bt_off_action); return; }
            if (!listening) {
                IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
                // Socket/GATT callbacks belong to a session. A queued ACL broadcast for the same
                // address can outlive it; the standard driver independently observes system profiles.
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(radio, f, Context.RECEIVER_EXPORTED);
                else app.registerReceiver(radio, f);
                listening = true;
            }
            phase = Phase.SELECTING;
            final int id = ++generation;
            DeviceSelector.select(app, adapter, AppPrefs.deviceAddress(app), (result, peer) -> {
                if (id != generation || phase != Phase.SELECTING) return;
                if (!AppPrefs.hasDisclaimer(app)) { stopWith(R.string.status_idle, R.string.tile_disclaimer_required); return; }
                if (!BluetoothAccess.connect(app)) { stopWith(R.string.status_no_perm, R.string.sub_no_perm_action); return; }
                switch (result) {
                    case AMBIGUOUS: stopWith(R.string.status_multiple_devices, R.string.sub_select_device_action); return;
                    case INCOMPLETE: stopWith(R.string.status_profiles_pending, R.string.sub_profiles_retry); return;
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
            address = peer.getAddress();
            String expected = host.expectedAddress();
            if (expected != null && !expected.equals(address)) {
                stopWith(R.string.status_pin_unavailable, R.string.sub_select_device_action); return;
            }
            source = DriverRegistry.create(app, peer);
            if (source.needsDiscoveryPermission() && !BluetoothAccess.cancelDiscovery(app)) {
                stopWith(R.string.status_no_perm, R.string.sub_no_perm_action); return;
            }
            phase = Phase.CONNECTING;
            reports = host.bind(source, address, peer.getName());
            source.addListener(reports);
            main.postDelayed(deadline, CONNECT_TIMEOUT_MS);
            source.connect(peer);
        } catch (SecurityException revoked) { stopWith(R.string.status_no_perm, R.string.sub_no_perm_action); }
        catch (RuntimeException error) { failed(error.toString()); }
    }
    void connected() { phase = Phase.LIVE; main.removeCallbacks(deadline); }
    boolean refreshPublicProfiles(Consumer<Boolean> completed) {
        TwsClient s = source;
        if (s == null || !live()) return false;
        return s.refreshPublicProfiles(found -> { if (current(s)) completed.accept(found && allowed()); });
    }
    void command(Consumer<TwsClient> action) {
        TwsClient s = source;
        if (!live() || !allowed()) return;
        s.execute(() -> { if (current(s) && s.isConnected() && allowed()) action.accept(s); });
    }
    void failed(String reason) {
        android.util.Log.d("WearEars", reason);
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
        if (listening) { app.unregisterReceiver(radio); listening = false; }
    }
}

package com.hdz.wearcontrol;

import android.bluetooth.*;
import android.content.Context;
import android.os.*;
import android.util.Log;
import java.util.UUID;

/** Android lifetime of one wire protocol. The owner creates a new instance on every attempt. */
final class WireSession implements TwsClient, WireDriver.Port {
    private final WireDriver driver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context app;
    private BluetoothDevice peer;
    private HandlerThread thread;
    private Handler actor;
    private volatile boolean closed, ending;
    private volatile RfcommLink rfcomm;
    private GattLink ble;
    private volatile Listener listener;

    WireSession(WireDriver driver) { this.driver = driver; }
    public void init(Context context) { app = context.getApplicationContext(); }
    public void addListener(Listener l) { listener = l; }
    public void removeListener(Listener l) { if (listener == l) listener = null; }
    public void connect(BluetoothDevice device) {
        if (thread != null || closed || listener == null) throw new IllegalStateException("Session already used or unbound");
        peer = device;
        thread = new HandlerThread("WearEars-" + driver.brandName());
        thread.start();
        actor = new Handler(thread.getLooper());
        final Listener events = listener;
        execute(() -> {
            driver.bind(device.getName(), this, events);
            if (!active()) return;
            if (driver.cancelDiscovery()) {
                BluetoothManager manager = app.getSystemService(BluetoothManager.class);
                if (manager != null && manager.getAdapter() != null) manager.getAdapter().cancelDiscovery();
            }
            ParcelUuid[] advertised = device.getUuids();
            UUID[] ids = new UUID[advertised == null ? 0 : advertised.length];
            for (int i = 0; i < ids.length; i++) ids[i] = advertised[i].getUuid();
            RfcommLink link = new RfcommLink(new AndroidSockets(device), new RfcommLink.Events() {
                public boolean post(Runnable r) { return enqueue(r); }
                public void after(Runnable r, long ms) { WireSession.this.after(r, ms); }
                public void cancel(Runnable r) { WireSession.this.cancel(r); }
                public void opened() { driver.onOpen(); }
                public void receive(byte[] data) { driver.received(data); }
                public void unavailable() { driver.openFallback(); }
                public void lost(String reason) { fail(reason); }
            });
            rfcomm = link;
            if (active()) link.start(driver.services(ids), driver.channels());
            else link.close();
        });
    }
    @Override public void useBle(UUID service, UUID notify, UUID write, long delay, long timeout) {
        if (!active() || ble != null) return;
        rfcomm.close();
        ble = new GattLink(actor, new GattLink.Events() {
            public void opened() { if (active()) driver.onOpen(); }
            public void receive(byte[] data) { if (active()) driver.received(data); }
            public void failed(String reason) { fail(reason); }
        }, service, notify, write, delay, driver.bleFragmentDelay(), driver.paceLastBleFragment());
        ble.open(app, peer, timeout);
    }
    private boolean active() { return !closed && !ending; }
    public boolean needsDiscoveryPermission() { return driver.cancelDiscovery(); }
    public boolean privateLink() { return true; }
    public boolean isConnected() { return active() && driver.isConnected(); }
    public int caps() { return driver.caps(); }
    public int potentialCaps() { return driver.potentialCaps(); }
    public int verifLevel() { return driver.verifLevel(); }
    public String brandName() { return driver.brandName(); }
    public SoundPreset[] equalizerPresets() { return driver.equalizerPresets(); }

    public void execute(Runnable work) { enqueue(work); }
    private boolean enqueue(Runnable work) {
        return active() && actor != null && actor.post(() -> guarded(work));
    }
    private void guarded(Runnable work) {
        if (!active()) return;
        try { work.run(); } catch (RuntimeException error) { fail(error.toString()); }
    }
    public void after(Runnable work, long ms) {
        if (active()) actor.postAtTime(() -> guarded(work), work, SystemClock.uptimeMillis() + ms);
    }
    public void cancel(Runnable work) { actor.removeCallbacksAndMessages(work); }
    public long millis() { return SystemClock.elapsedRealtime(); }
    public void trace(String tag, String message) { if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, message); }
    public void send(byte[] data, Runnable flushed) {
        if (!active()) return;
        if (ble != null) ble.send(data, flushed);
        else if (rfcomm != null) rfcomm.send(data, flushed);
    }
    public void fail(String reason) {
        if (!active()) return;
        boolean ready = driver.isConnected();
        ending = true;
        release();
        main.postDelayed(() -> {
            Listener l = listener;
            if (closed || l == null) return;
            if (ready) l.onDisconnected(reason); else l.onError(reason);
        }, ready ? 0 : driver.failureCooldown());
    }
    public void disconnect() {
        closed = true;
        RfcommLink link = rfcomm;
        if (link != null) link.close();
        if (actor != null) actor.postAtFrontOfQueue(this::release);
    }
    private void release() {
        if (rfcomm != null) rfcomm.close();
        if (ble != null) ble.close();
        driver.onClose();
        actor.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }
    public void queryBattery() { driver.queryBattery(); }
    public void queryNoise() { driver.queryNoise(); }
    public void querySpatial() { driver.querySpatial(); }
    public void queryDeepX() { driver.queryDeepX(); }
    public void queryEqualizer() { driver.queryEqualizer(); }
    public void setNoiseMode(int mode) { changeNoise(mode, () -> { }); }
    public void changeNoise(int mode, Runnable flushed) {
        if (isConnected() && (caps() & CAP_NOISE) != 0) driver.changeNoise(mode, flushed);
    }
    public void setSpatial(boolean on) {
        if (isConnected() && (caps() & CAP_SPATIAL) != 0) driver.setSpatial(on);
    }
    public void setDeepX(int mode) { setEqualizer(mode); }
    public void setEqualizer(int mode) {
        if (isConnected() && (caps() & (CAP_EQUALIZER | CAP_DEEPX)) != 0) driver.setEqualizer(mode);
    }
}

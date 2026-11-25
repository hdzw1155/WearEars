package com.hdz.wearcontrol;

import android.bluetooth.*;
import android.content.Context;
import android.os.*;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Android lifetime of one wire protocol. The owner creates a new instance on every attempt. */
final class WireSession implements TwsClient, WireDriver.Port {
    private final WireDriver driver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context app;
    private BluetoothDevice peer;
    private HandlerThread thread;
    private Handler actor;
    private volatile boolean closed, ending, driverBound, released;
    private volatile RfcommLink rfcomm;
    private GattLink ble;
    private volatile Listener listener;
    private final Listener driverEvents = new Listener() {
        @Override public void onConnected(String deviceName) {
            Listener l = listener;
            if (!closed && l != null) l.onConnected(deviceName);
        }
        @Override public void onDisconnected(String reason) {
            Listener l = listener;
            if (!closed && l != null) l.onDisconnected(reason);
        }
        @Override public void onError(String msg) {
            Listener l = listener;
            if (!closed && l != null) l.onError(msg);
        }
        @Override public void onBattery(int left, int right, int box, int chargeFlags) {
            Listener l = listener;
            if (!closed && l != null) l.onBattery(left, right, box, chargeFlags);
        }
        @Override public void onNoiseMode(int mode) {
            Listener l = listener;
            if (!closed && l != null) l.onNoiseMode(mode);
        }
        @Override public void onSpatial(Boolean state) {
            Listener l = listener;
            if (!closed && l != null) l.onSpatial(state);
        }
        @Override public void onDeepX(Integer mode) {
            Listener l = listener;
            if (!closed && l != null) l.onDeepX(mode);
        }
        @Override public void onEqualizer(Integer mode) {
            Listener l = listener;
            if (!closed && l != null) l.onEqualizer(mode);
        }
        @Override public void onFeatureSpec(FeatureDescriptor spec) {
            Listener l = listener;
            if (!closed && l != null) l.onFeatureSpec(spec);
        }
        @Override public void onFeatureValue(FeatureId id, int value) {
            Listener l = listener;
            if (!closed && l != null) l.onFeatureValue(id, value);
        }
        @Override public void onFeatureText(FeatureId id, String text) {
            Listener l = listener;
            if (!closed && l != null) l.onFeatureText(id, text);
        }
    };

    WireSession(WireDriver driver) { this.driver = driver; }
    void prebind(String name) { bindDriver(name); }
    private synchronized void bindDriver(String name) {
        if (!driverBound) {
            driver.bind(name, this, driverEvents);
            driverBound = true;
        }
    }
    public void init(Context context) { app = context.getApplicationContext(); }
    public void addListener(Listener l) { listener = l; }
    public void removeListener(Listener l) { if (listener == l) listener = null; }
    public void connect(BluetoothDevice device) {
        if (thread != null || closed || listener == null) throw new IllegalStateException("Session already used or unbound");
        peer = device;
        thread = new HandlerThread("WearEars-" + driver.brandName());
        thread.start();
        actor = new Handler(thread.getLooper());
        execute(() -> {
            bindDriver(device.getName());
            if (!active()) return;
            if (driver.cancelDiscovery()) {
                try {
                    BluetoothManager manager = app.getSystemService(BluetoothManager.class);
                    if (manager != null && manager.getAdapter() != null) manager.getAdapter().cancelDiscovery();
                } catch (SecurityException ignored) { }
            }
            ParcelUuid[] advertised = device.getUuids();
            List<UUID> validIds = new ArrayList<>();
            if (advertised != null) {
                for (ParcelUuid pu : advertised) {
                    if (pu != null && pu.getUuid() != null) validIds.add(pu.getUuid());
                }
            }
            UUID[] ids = validIds.toArray(new UUID[0]);
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
        RfcommLink link = rfcomm;
        if (link != null) link.close();
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
    public FeatureDescriptor[] featureDescriptors() { return driver.featureDescriptors(); }

    boolean tryExecute(Runnable work) { return enqueue(work); }
    public void execute(Runnable work) { enqueue(work); }
    private boolean enqueue(Runnable work) {
        return active() && actor != null && actor.post(() -> guarded(work));
    }
    private void guarded(Runnable work) {
        if (!active()) return;
        try { work.run(); } catch (RuntimeException error) { fail(error.toString()); }
    }
    public void after(Runnable work, long ms) {
        if (active() && actor != null) actor.postAtTime(() -> guarded(work), work, SystemClock.uptimeMillis() + ms);
    }
    public void cancel(Runnable work) {
        if (actor != null) actor.removeCallbacksAndMessages(work);
    }
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
        else release();
    }
    private synchronized void release() {
        if (released) return;
        released = true;
        if (rfcomm != null) rfcomm.close();
        if (ble != null) ble.close();
        if (driverBound) driver.onClose();
        if (actor != null) actor.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
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
    public void queryFeature(FeatureId id) {
        if (id != null) driver.queryFeature(id);
    }
    public void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (id != null && isConnected()) driver.writeFeature(id, value, flushed);
    }
}

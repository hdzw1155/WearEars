package com.hdz.wearcontrol;

import java.util.UUID;

/** A fresh protocol instance belongs to exactly one actor and one peer. */
public abstract class WireDriver implements Controls {
    public interface Port {
        void send(byte[] bytes, Runnable flushed);
        void after(Runnable work, long delay);
        void cancel(Runnable work);
        void useBle(UUID service, UUID notify, UUID write, long delay, long timeout);
        void fail(String message);
        long millis();
        void trace(String tag, String message);
    }

    private Port port;
    private Listener listener;
    private String name = "";
    private boolean open;
    private volatile boolean ready;
    private Runnable legacyBridgeFlushed;
    private boolean legacyBridgeDispatched;

    public final void bind(String name, Port port, Listener listener) {
        if (this.port != null) throw new IllegalStateException("Protocol already bound");
        this.name = name == null ? "" : name;
        this.port = port;
        this.listener = listener;
        prepare();
    }

    protected abstract UUID[] services(UUID[] advertised);
    protected int[] channels() { return new int[0]; }
    protected boolean cancelDiscovery() { return false; }
    protected long failureCooldown() { return 0; }
    protected int bleFragmentDelay() { return 0; }
    protected boolean paceLastBleFragment() { return false; }
    protected String logTag() { return brandName(); }
    protected String unnamedDevice() { return brandName(); }
    protected String connectionFailure() { return "Control channel unavailable"; }
    protected void prepare() { }
    protected abstract void opened();
    protected abstract void received(byte[] bytes);
    protected void closed() { }
    protected void openFallback() { fail(connectionFailure()); }

    final void onOpen() { open = true; opened(); }
    final void onClose() { open = ready = false; closed(); }
    protected final Listener events() { return listener; }
    protected final String deviceName() { return name; }
    protected final boolean transportOpen() { return open; }
    public final boolean isConnected() { return ready; }
    protected final void ready() {
        if (ready || !open) return;
        ready = true;
        listener.onConnected(name.isEmpty() ? unnamedDevice() : name);
    }
    protected final void openBle(UUID service, UUID notify, UUID write, long delay, long timeout) {
        port.useBle(service, notify, write, delay, timeout);
    }
    protected final void write(byte[] frame) { writeThen(frame, null); }
    protected final void write(byte[] frame, String trace) {
        if (trace != null) port.trace(logTag(), trace);
        write(frame);
    }
    protected final void writeThen(byte[] frame, Runnable flushed) {
        Runnable callback = flushed;
        if (callback == null && legacyBridgeFlushed != null) {
            callback = legacyBridgeFlushed;
            legacyBridgeFlushed = null;
            legacyBridgeDispatched = true;
        }
        if (open) port.send(frame, callback);
    }
    protected final void schedule(Runnable work, long delay) { port.after(work, delay); }
    protected final void cancel(Runnable work) { port.cancel(work); }
    protected final long clockMillis() { return port.millis(); }
    protected final void trace(String tag, String message) { port.trace(tag, message); }
    protected final void fail(String message) { port.fail(message); }
    protected final void reportError(String message) { listener.onError(message); }
    protected final void confirmFeature(FeatureDescriptor spec, int initialValue) {
        if (spec == null || listener == null) return;
        listener.onFeatureSpec(spec);
        listener.onFeatureValue(spec.id, initialValue);
    }
    protected final void confirmFeature(FeatureDescriptor spec) {
        if (spec != null && listener != null) listener.onFeatureSpec(spec);
    }
    protected final void reportFeature(FeatureId id, int value) {
        if (id != null && listener != null) listener.onFeatureValue(id, value);
    }
    protected final void reportFeatureText(FeatureId id, String text) {
        if (id != null && listener != null) listener.onFeatureText(id, text == null ? "" : text);
    }
    public abstract void changeNoise(int mode, Runnable flushed);
    @Override public final void setNoiseMode(int mode) { changeNoise(mode, () -> { }); }
    @Override public void querySpatial() { }
    @Override public void setSpatial(boolean enabled) { }
    @Override public void queryDeepX() { }
    @Override public void setDeepX(int mode) { }
    @Override public FeatureDescriptor[] featureDescriptors() { return new FeatureDescriptor[0]; }
    @Override public void queryFeature(FeatureId id) {
        if (id == FeatureId.SPATIAL_AUDIO) querySpatial();
        else if (id == FeatureId.EQUALIZER) queryEqualizer();
    }
    @Override public void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (id == FeatureId.SPATIAL_AUDIO || id == FeatureId.EQUALIZER) {
            legacyBridgeFlushed = flushed;
            legacyBridgeDispatched = false;
            try {
                if (id == FeatureId.SPATIAL_AUDIO) setSpatial(value != 0);
                else setEqualizer(value);
            } finally {
                legacyBridgeFlushed = null;
                legacyBridgeDispatched = false;
            }
        }
    }
    @Override public int verifLevel() { return VERIF_UNTESTED; }
}

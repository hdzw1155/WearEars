package com.hdz.wearcontrol;

/** Captures the originating client and active intent at receipt, before crossing to the main actor. */
final class SessionReports implements Controls.Listener {
    private final ControlHub hub;
    private final TwsClient source;
    SessionReports(ControlHub hub, TwsClient source) { this.hub = hub; this.source = source; }
    private void deliver(Runnable task) {
        hub.main.post(() -> { if (hub.links.current(source)) task.run(); });
    }
    private void data(Runnable task) {
        int caps = source.caps(), potential = source.potentialCaps(), evidence = source.verifLevel();
        SoundPreset[] presets = source.equalizerPresets();
        deliver(() -> {
            if (hub.state.connected) {
                hub.sync(caps, potential, presets, evidence);
                task.run();
            }
        });
    }
    public void onConnected(String name) {
        int caps = source.caps(), potential = source.potentialCaps(), evidence = source.verifLevel();
        SoundPreset[] presets = source.equalizerPresets();
        deliver(() -> { hub.sync(caps, potential, presets, evidence); hub.ready(source, name); });
    }
    public void onDisconnected(String reason) { deliver(() -> hub.links.failed(reason)); }
    public void onError(String msg) {
        deliver(() -> {
            if (!source.isConnected()) hub.links.failed(msg);
            else if (!hub.transactions.busy()) for (ControlHub.UiListener l : hub.views()) l.onError(msg);
        });
    }
    public void onBattery(int left, int right, int box, int flags) {
        Transactions.Request request = hub.transactions.receipt();
        data(() -> {
            DeviceState s = hub.state;
            boolean single = (s.caps & Controls.CAP_BATTERY_SINGLE) != 0;
            if (!DeviceState.known(left) && (single || !DeviceState.known(right) && !DeviceState.known(box))) return;
            s.battery(left, right, box, flags);
            if (single) TileStore.saveBatterySingle(hub.app, left);
            else TileStore.saveBattery(hub.app, left, right, box, flags);
            TileStore.noteOk(hub.app); TileStore.push(hub.app);
            for (ControlHub.UiListener l : hub.views()) l.onBattery(s.left, s.right, s.box, s.charge);
            hub.transactions.battery(request);
        });
    }
    public void onNoiseMode(int mode) {
        Transactions.Request request = hub.transactions.receipt();
        boolean dispatched = Transactions.dispatched(request);
        data(() -> {
            if (mode < -1 || mode > 2) return;
            hub.state.mode = mode;
            TileStore.saveMode(hub.app, mode);
            if (mode >= 0) TileStore.noteOk(hub.app);
            TileStore.push(hub.app);
            for (ControlHub.UiListener l : hub.views()) l.onNoiseMode(mode);
            hub.transactions.noise(request, mode, dispatched);
        });
    }
    public void onSpatial(Boolean value) {
        data(() -> { hub.state.spatial = value; hub.state.spatialRevision++;
            for (ControlHub.UiListener l : hub.views()) l.onSpatial(value);
        });
    }
    public void onDeepX(Integer value) { onEqualizer(value); }
    public void onEqualizer(Integer value) {
        data(() -> { hub.state.equalizer = value; hub.state.equalizerRevision++;
            for (ControlHub.UiListener l : hub.views()) l.onEqualizer(value);
        });
    }
}

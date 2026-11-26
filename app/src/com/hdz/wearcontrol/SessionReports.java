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
            if (hub.live()) {
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
            if (!source.isConnected()) {
                hub.links.failed(msg);
            } else {
                if (hub.transactions.busy()) hub.transactions.finish(false);
                for (ControlHub.UiListener l : hub.views()) l.onError(msg);
            }
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
            hub.onBurstDataReceived();
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
            hub.featureTx.onFeatureReady(FeatureId.ANC_LEVEL);
            hub.featureTx.onFeatureReady(FeatureId.TRANS_MODE);
            hub.onBurstDataReceived();
        });
    }
    public void onSpatial(Boolean value) {
        FeatureTransactions.Request request = hub.featureTx.receipt(FeatureId.SPATIAL_AUDIO);
        boolean dispatched = FeatureTransactions.dispatched(request);
        data(() -> {
            if (value == null) {
                hub.state.spatial = null;
                hub.state.spatialRevision++;
                hub.state.clearFeatureValue(FeatureId.SPATIAL_AUDIO);
            } else {
                hub.state.featureValue(FeatureId.SPATIAL_AUDIO, value ? 1 : 0);
            }
            for (ControlHub.UiListener l : hub.views()) {
                l.onSpatial(value);
                if (value != null) l.onFeatureValue(FeatureId.SPATIAL_AUDIO, value ? 1 : 0);
            }
            if (value != null) {
                hub.featureTx.report(request, FeatureId.SPATIAL_AUDIO, value ? 1 : 0, dispatched);
            }
        });
    }
    public void onDeepX(Integer value) { onEqualizer(value); }
    public void onEqualizer(Integer value) {
        FeatureTransactions.Request request = hub.featureTx.receipt(FeatureId.EQUALIZER);
        boolean dispatched = FeatureTransactions.dispatched(request);
        data(() -> {
            if (value == null) {
                hub.state.equalizer = null;
                hub.state.equalizerRevision++;
                hub.state.clearFeatureValue(FeatureId.EQUALIZER);
            } else {
                hub.state.featureValue(FeatureId.EQUALIZER, value);
            }
            for (ControlHub.UiListener l : hub.views()) {
                l.onEqualizer(value);
                if (value != null) l.onFeatureValue(FeatureId.EQUALIZER, value);
            }
            if (value != null) {
                hub.featureTx.report(request, FeatureId.EQUALIZER, value, dispatched);
            }
        });
    }
    public void onFeatureSpec(FeatureDescriptor spec) {
        if (spec == null) return;
        data(() -> {
            hub.state.featureSpec(spec);
            for (ControlHub.UiListener l : hub.views()) l.onFeatureSpec(spec);
            hub.featureTx.onFeatureReady(spec.id);
        });
    }
    public void onFeatureValue(FeatureId id, int value) {
        if (id == null) return;
        FeatureTransactions.Request request = hub.featureTx.receipt(id);
        boolean dispatched = FeatureTransactions.dispatched(request);
        data(() -> {
            hub.state.featureValue(id, value);
            hub.onFeatureValueReported(id, value);
            for (ControlHub.UiListener l : hub.views()) {
                l.onFeatureValue(id, value);
                if (id == FeatureId.SPATIAL_AUDIO) l.onSpatial(value != 0);
                else if (id == FeatureId.EQUALIZER) l.onEqualizer(value);
            }
            hub.featureTx.report(request, id, value, dispatched);
            if (id == FeatureId.WEAR_STATUS) {
                hub.featureTx.onFeatureReady(FeatureId.FIND_MY_BUDS);
            }
        });
    }
    public void onFeatureText(FeatureId id, String text) {
        if (id == null) return;
        String safe = text == null ? "" : text;
        data(() -> {
            hub.state.featureText(id, safe);
            for (ControlHub.UiListener l : hub.views()) l.onFeatureText(id, safe);
        });
    }
}

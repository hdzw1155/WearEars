package com.hdz.wearcontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Binds the two independent state machines to foreground and tile demand. All UI updates run on main. */
final class ControlHub implements ConnectionOwner.Host, Transactions.Host, FeatureTransactions.Host {
    interface UiListener extends Controls.Listener {
        void onConnectionState(int status, int hint);
        void onOperationChanged(int target, boolean active, boolean success);
        default void onFeatureChanged(FeatureId id, int value, boolean active, boolean success) { }
    }
    private static volatile ControlHub instance;
    static synchronized ControlHub get(Context ctx) {
        if (instance == null) instance = new ControlHub(ctx.getApplicationContext());
        return instance;
    }
    static boolean hasActiveLink(String address) {
        ControlHub hub = instance;
        return hub != null && address != null && hub.links.allowed() && hub.links.privateLink(address);
    }

    final Context app;
    final Handler main = new Handler(Looper.getMainLooper());
    final DeviceState state = new DeviceState();
    final Transactions transactions = new Transactions(this);
    final FeatureTransactions featureTx = new FeatureTransactions(this);
    final ConnectionOwner links;
    private final ArrayList<UiListener> observers = new ArrayList<>();
    private final Runnable poll = this::poll;
    private static final long BURST_INITIAL_IDLE_MS = 1800L;
    private static final long BURST_SYNCED_IDLE_MS = 900L;
    private static final long BURST_ACTION_IDLE_MS = 450L;
    private final Runnable burstPark = this::maybeParkForBurst;
    private Runnable deferredReset;
    private String tileAddress;
    private int evidence = Controls.VERIF_UNTESTED;
    ControlHub(Context app) { this.app = app; links = new ConnectionOwner(app, main, this); }
    DeviceState state() { return state; }
    int verifLevel() { return evidence; }
    boolean isConnected() { return links.live() || (state.connected && state.parked); }
    boolean isBurstMode() { return DeviceForm.isPhone(app) && AppPrefs.phoneBurstMode(app); }
    int caps() { return state.caps; }
    void addListener(UiListener listener) { if (!observers.contains(listener)) observers.add(listener); }
    void removeListener(UiListener listener) { observers.remove(listener); }
    UiListener[] views() { return observers.toArray(new UiListener[0]); }
    void connect() {
        if (links.isParked() && state.connected) {
            for (UiListener l : views()) l.onConnectionState(R.string.status_parked, 0);
            return;
        }
        links.manual();
        if (live()) {
            for (UiListener l : views()) l.onConnected(state.name);
            if (!isBurstMode()) poll();
            if (transactions.busy()) for (UiListener l : views()) l.onOperationChanged(transactions.pending(), true, false);
        }
    }
    void disconnect() {
        if (foreground()) return;
        stopFindBuds();
        main.removeCallbacks(poll);
        main.removeCallbacks(burstPark);
        if (tileAddress == null) transactions.finish(false);
        links.releaseIfIdle();
    }
    void deviceChanged() {
        TileLedger.invalidate(app);
        cancelDeferredReset();
        main.removeCallbacks(burstPark);
        boolean needFlushStop = featureTx.isFindBudsRinging() && live();
        stopFindBuds();
        if (needFlushStop) {
            // 给 FIND_MY_BUDS=0 停响帧留出短时写出窗口，避免立刻 disconnect() 把停响帧静默丢弃
            deferredReset = () -> {
                deferredReset = null;
                finishDeviceReset();
            };
            main.postDelayed(deferredReset, 320L);
            return;
        }
        finishDeviceReset();
    }
    private void finishDeviceReset() {
        transactions.finish(false); featureTx.linkEnded(); links.reset(); links.releaseIfIdle();
        if (foreground()) links.manual();
    }
    private void cancelDeferredReset() {
        if (deferredReset != null) {
            main.removeCallbacks(deferredReset);
            deferredReset = null;
        }
    }
    boolean isFindBudsRinging() {
        return featureTx.isFindBudsRinging();
    }
    void onBurstDataReceived() {
        if (!isBurstMode() || !links.activePrivateLink()) return;
        if (transactions.busy() || featureTx.busy() || featureTx.isFindBudsRinging()) return;
        scheduleBurstPark(BURST_SYNCED_IDLE_MS);
    }
    @Override public boolean canSoftParkOnDrop() {
        return isBurstMode() && state.hasSyncedData()
                && !transactions.busy() && !featureTx.busy() && !featureTx.isFindBudsRinging();
    }
    void connectionModeChanged() {
        main.removeCallbacks(burstPark);
        if (isBurstMode()) {
            main.removeCallbacks(poll);
            if (links.activePrivateLink()) scheduleBurstPark(BURST_ACTION_IDLE_MS);
        } else {
            if (links.isParked() && foreground()) links.wakeFromPark();
            else if (live()) schedulePoll();
        }
    }
    void perform(int mode) {
        TileLedger.invalidate(app);
        main.removeCallbacks(burstPark);
        start(mode, null);
    }
    void performFromTile(int mode, String address) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> performFromTile(mode, address)); return; }
        String pin = AppPrefs.deviceAddress(app);
        if (address == null || address.isEmpty() || !address.equals(TileStore.deviceAddress(app))
                || !pin.isEmpty() && !pin.equals(address)
                || !links.address().isEmpty() && !address.equals(links.address())) return;
        start(mode, address);
    }
    private void start(int mode, String address) {
        if (!links.allowed() || mode < -1 || mode > 2) return;
        String previousTileAddress = tileAddress;
        tileAddress = address;
        if (!transactions.begin(mode)) {
            tileAddress = previousTileAddress;
            return;
        }
        if (transactions.busy()) {
            if (links.isParked()) links.wakeFromPark();
            else links.manual();
        }
    }
    public boolean needed() {
        return foreground() || transactions.busy()
                || (featureTx.busy(FeatureId.FIND_MY_BUDS) && featureTx.pending(FeatureId.FIND_MY_BUDS) == 0);
    }
    public boolean foreground() { return !observers.isEmpty(); }
    public String expectedAddress() { return tileAddress; }
    public Controls.Listener bind(TwsClient source, String address, String name, boolean wakingFromPark) {
        if (wakingFromPark && state.parked && address != null && address.equalsIgnoreCase(state.address)) {
            state.resumeParked(address, name);
            featureTx.resetWearStatus();
        } else {
            state.begin(address, name, source.caps(), source.potentialCaps());
            featureTx.resetWearStatus();
        }
        state.limitation = source.privateLink() ? "" : "standard";
        evidence = source.verifLevel();
        TileStore.selectDevice(app, address, name, state.caps, state.potentialCaps, evidence);
        for (UiListener l : views()) l.onConnectionState(R.string.status_connecting, 0);
        return new SessionReports(this, source);
    }
    void ready(TwsClient s, String name) {
        links.connected();
        state.connected = true;
        state.parked = false;
        state.limitation = s.privateLink() ? "" : "standard";
        if (name != null && !name.isEmpty()) state.name = name;
        TileStore.saveName(app, state.name);
        for (UiListener l : views()) l.onConnected(state.name);
        if (transactions.busy()) transactions.connected();
        if (featureTx.busy()) featureTx.connected();
        if (links.live()) {
            // Let brand handshakes deliver their first evidence before filling missing fields.
            main.postDelayed(() -> { if (links.current(s) && links.live()) {
                if (!transactions.busy()) read(true, true);
                effects();
            }}, 450);
            schedulePoll();
            scheduleBurstPark(BURST_INITIAL_IDLE_MS);
        }
    }
    @Override public void parked() {
        main.removeCallbacks(poll);
        main.removeCallbacks(burstPark);
        state.enterParked();
        featureTx.resetWearStatus();
        for (UiListener l : views()) l.onConnectionState(R.string.status_parked, 0);
    }
    private void scheduleBurstPark(long delayMs) {
        main.removeCallbacks(burstPark);
        if (!isBurstMode() || !links.activePrivateLink()) return;
        if (transactions.busy() || featureTx.busy() || featureTx.isFindBudsRinging()) return;
        main.postDelayed(burstPark, delayMs);
    }
    private void maybeParkForBurst() {
        if (!isBurstMode() || !links.activePrivateLink()) return;
        if (transactions.busy() || featureTx.busy() || featureTx.isFindBudsRinging()) return;
        links.park();
    }
    void sync(int caps, int potential, SoundPreset[] presets, int level) {
        evidence = level;
        state.capabilities(caps, potential);
        state.presets = presets;
        if (TileStore.saveCaps(app, caps, potential, level)) TileStore.push(app);
    }
    public void reset() {
        state.disconnect();
        transactions.linkEnded();
        featureTx.linkEnded();
        main.removeCallbacks(poll);
        main.removeCallbacks(burstPark);
        TileStore.push(app);
    }
    public void unavailable(int status, int hint) {
        main.removeCallbacks(burstPark);
        transactions.finish(false);
        featureTx.linkEnded();
        for (UiListener l : views()) l.onConnectionState(status, hint);
    }
    public boolean live() { return links.live() && state.connected; }
    public int potential() { return state.potentialCaps; }
    public int confirmed() { return state.caps; }
    public int mode() { return state.mode; }
    public boolean refreshPublicProfiles(Consumer<Boolean> completed) { return links.refreshPublicProfiles(completed); }
    public void read(boolean battery, boolean noise) {
        links.command(s -> {
            if (battery && (s.potentialCaps() & (Controls.CAP_BATTERY | Controls.CAP_BATTERY_SINGLE)) != 0) s.queryBattery();
            if (noise && (s.potentialCaps() & Controls.CAP_NOISE) != 0) s.queryNoise();
        });
    }
    public void write(int mode, BooleanSupplier claim, Runnable flushed) {
        Runnable abort = () -> transactions.abortQueued(claim);
        links.command(s -> {
            if ((s.caps() & Controls.CAP_NOISE) == 0) {
                main.post(abort);
                return;
            }
            if (!claim.getAsBoolean()) return;
            s.changeNoise(mode, flushed);
        }, abort);
    }
    public void reopen() { links.reset(); links.ensure(); }
    public void later(Runnable work, long delay) { main.postDelayed(work, delay); }
    public void cancel(Runnable work) { if (work != null) main.removeCallbacks(work); }
    public void changed(int target, boolean active, boolean success) {
        if (active && target >= 0) featureTx.onTopModeBegin(target);
        if (active) main.removeCallbacks(burstPark);
        TileStore.pending(app, active ? target : -1);
        for (UiListener l : views()) l.onOperationChanged(target, active, success);
        if (!active) {
            tileAddress = null;
            if (!success) for (UiListener l : views()) l.onError(AppLocale.localizedContext(app).getString(
                    target < 0 ? R.string.status_refresh_unconfirmed : R.string.status_mode_failed));
            links.releaseIfIdle();
            scheduleBurstPark(BURST_ACTION_IDLE_MS);
        }
    }
    void performFeature(FeatureId id, int value) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> performFeature(id, value));
            return;
        }
        if (id == null || !links.allowed() || !isConnected()) return;
        main.removeCallbacks(burstPark);
        if (id == FeatureId.NOISE_MODE) {
            perform(value);
            return;
        }
        if (!writable(id, value)) return;
        boolean started = featureTx.begin(id, value);
        if (started && !live()) {
            if (links.isParked()) links.wakeFromPark();
            else links.manual();
        }
    }
    void stopFindBuds() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::stopFindBuds);
            return;
        }
        featureTx.stopFindBuds();
        if (featureTx.busy(FeatureId.FIND_MY_BUDS) && !live()) {
            if (links.isParked()) links.wakeFromPark();
            else links.manual();
        }
    }
    int pendingFeature(FeatureId id) {
        return id == FeatureId.NOISE_MODE ? transactions.pending() : featureTx.pending(id);
    }
    void onFeatureValueReported(FeatureId id, int value) {
        if (id == FeatureId.WEAR_STATUS) {
            featureTx.onWearStatus(value);
        } else if (id == FeatureId.FIND_MY_BUDS && value == 0) {
            featureTx.onFindBudsStopped();
            scheduleBurstPark(BURST_ACTION_IDLE_MS);
        }
    }
    @Override public boolean featureReady(FeatureId id) {
        if (!live() || id == null) return false;
        if (id == FeatureId.FIND_MY_BUDS && featureTx.pending(FeatureId.FIND_MY_BUDS) > 0
                && state.featureSpec(FeatureId.WEAR_STATUS) != null
                && state.wearStatus == DeviceState.WEAR_UNKNOWN) {
            return false;
        }
        return state.isFeatureConfirmedLive(id);
    }
    @Override public boolean writable(FeatureId id, int value) {
        if (id == null || !isConnected()) return false;
        FeatureDescriptor spec = state.featureSpec(id);
        if (spec != null) {
            if (!state.isFeatureWritable(id) || !spec.isValidValue(value)) return false;
            if (id == FeatureId.FIND_MY_BUDS && value > 0
                    && (state.isAnyBudInEar() || state.isTargetInEar(value))) return false;
            if ((id == FeatureId.SPATIAL_SCENE || id == FeatureId.HEAD_TRACKING)
                    && state.featureSpec(FeatureId.SPATIAL_AUDIO) != null) {
                Integer sp = state.featureValue(FeatureId.SPATIAL_AUDIO);
                if (sp == null || sp.intValue() == 0) return false;
            }
            return true;
        }
        if (id == FeatureId.SPATIAL_AUDIO) {
            return (value == 0 || value == 1)
                    && state.capability(Controls.CAP_SPATIAL) == DeviceState.SUPPORTED
                    && state.spatial != null;
        }
        if (id == FeatureId.EQUALIZER && (state.caps & (Controls.CAP_EQUALIZER | Controls.CAP_DEEPX)) != 0) {
            for (SoundPreset p : state.presets) {
                if (p.id == value) return true;
            }
        }
        return false;
    }
    @Override public Integer currentValue(FeatureId id) { return state.featureValue(id); }
    @Override public void read(FeatureId id) {
        if (id != null) links.command(s -> s.queryFeature(id));
    }
    @Override public void write(FeatureId id, int value, BooleanSupplier claim, Runnable flushed) {
        Runnable abort = () -> featureTx.abortQueued(id, claim);
        links.command(s -> {
            if (!claim.getAsBoolean()) return;
            s.writeFeature(id, value, flushed);
        }, abort);
    }
    @Override public void changed(FeatureId id, int value, boolean active, boolean success) {
        if (active) main.removeCallbacks(burstPark);
        for (UiListener l : views()) l.onFeatureChanged(id, value, active, success);
        if (!active) {
            links.releaseIfIdle();
            scheduleBurstPark(BURST_ACTION_IDLE_MS);
        }
    }
    void setSpatial(boolean enabled) { performFeature(FeatureId.SPATIAL_AUDIO, enabled ? 1 : 0); }
    void setEqualizer(int id) { performFeature(FeatureId.EQUALIZER, id); }
    private void effects() {
        links.command(s -> {
            boolean queriedSpatial = false, queriedEqualizer = false;
            for (FeatureDescriptor spec : s.featureDescriptors()) {
                if (spec == null || spec.id == FeatureId.NOISE_MODE) continue;
                if (spec.id == FeatureId.SPATIAL_AUDIO) queriedSpatial = true;
                if (spec.id == FeatureId.EQUALIZER) queriedEqualizer = true;
                if (!featureTx.busy(spec.id)) s.queryFeature(spec.id);
            }
            if (!queriedSpatial && (s.potentialCaps() & Controls.CAP_SPATIAL) != 0) s.querySpatial();
            if (!queriedEqualizer && (s.potentialCaps() & (Controls.CAP_DEEPX | Controls.CAP_EQUALIZER)) != 0) s.queryEqualizer();
        });
    }
    private void poll() {
        if (foreground() && live() && !isBurstMode()) {
            read(true, true);
            if (state.featureSpec(FeatureId.WEAR_STATUS) != null) read(FeatureId.WEAR_STATUS);
            schedulePoll();
        }
    }
    private void schedulePoll() {
        main.removeCallbacks(poll);
        if (foreground() && live() && !isBurstMode()) main.postDelayed(poll, 60000);
    }
}

package com.hdz.wearcontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Binds the two independent state machines to foreground and tile demand. All UI updates run on main. */
final class ControlHub implements ConnectionOwner.Host, Transactions.Host {
    interface UiListener extends Controls.Listener {
        void onConnectionState(int status, int hint);
        void onOperationChanged(int target, boolean active, boolean success);
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
    final ConnectionOwner links;
    private final ArrayList<UiListener> observers = new ArrayList<>();
    private final Runnable poll = this::poll;
    private String tileAddress;
    private int evidence = Controls.VERIF_UNTESTED;
    ControlHub(Context app) { this.app = app; links = new ConnectionOwner(app, main, this); }
    DeviceState state() { return state; }
    int verifLevel() { return evidence; }
    boolean isConnected() { return links.live(); }
    int caps() { return state.caps; }
    void addListener(UiListener listener) { if (!observers.contains(listener)) observers.add(listener); }
    void removeListener(UiListener listener) { observers.remove(listener); }
    UiListener[] views() { return observers.toArray(new UiListener[0]); }
    void connect() {
        links.manual();
        if (live()) {
            for (UiListener l : views()) l.onConnected(state.name);
            poll();
            if (transactions.busy()) for (UiListener l : views()) l.onOperationChanged(transactions.pending(), true, false);
        }
    }
    void disconnect() {
        if (foreground()) return;
        main.removeCallbacks(poll);
        if (tileAddress == null) transactions.finish(false);
        links.releaseIfIdle();
    }
    void deviceChanged() {
        TileLedger.invalidate(app);
        transactions.finish(false); links.reset(); links.releaseIfIdle();
        if (foreground()) links.manual();
    }
    void perform(int mode) { TileLedger.invalidate(app); start(mode, null); }
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
        tileAddress = address;
        transactions.begin(mode);
        if (transactions.busy()) links.manual();
    }
    public boolean needed() { return foreground() || transactions.busy(); }
    public boolean foreground() { return !observers.isEmpty(); }
    public String expectedAddress() { return tileAddress; }
    public Controls.Listener bind(TwsClient source, String address, String name) {
        state.begin(address, name, source.caps(), source.potentialCaps());
        evidence = source.verifLevel();
        TileStore.selectDevice(app, address, name, state.caps, state.potentialCaps, evidence);
        for (UiListener l : views()) l.onConnectionState(R.string.status_connecting, 0);
        return new SessionReports(this, source);
    }
    void ready(TwsClient s, String name) {
        links.connected();
        state.connected = true;
        if (name != null && !name.isEmpty()) state.name = name;
        TileStore.saveName(app, state.name);
        for (UiListener l : views()) l.onConnected(state.name);
        if (transactions.busy()) transactions.connected();
        if (links.live()) {
            // Let brand handshakes deliver their first evidence before filling missing fields.
            main.postDelayed(() -> { if (links.current(s) && links.live()) {
                if (!transactions.busy()) read(true, true);
                effects();
            }}, 450);
            schedulePoll();
        }
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
        main.removeCallbacks(poll);
        TileStore.push(app);
    }
    public void unavailable(int status, int hint) {
        transactions.finish(false);
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
        links.command(s -> {
            if ((s.caps() & Controls.CAP_NOISE) == 0 || !claim.getAsBoolean()) return;
            s.changeNoise(mode, flushed);
        });
    }
    public void reopen() { links.reset(); links.ensure(); }
    public void later(Runnable work, long delay) { main.postDelayed(work, delay); }
    public void cancel(Runnable work) { if (work != null) main.removeCallbacks(work); }
    public void changed(int target, boolean active, boolean success) {
        TileStore.pending(app, active ? target : -1);
        for (UiListener l : views()) l.onOperationChanged(target, active, success);
        if (!active) {
            tileAddress = null;
            if (!success) for (UiListener l : views()) l.onError(AppLocale.localizedContext(app).getString(
                    target < 0 ? R.string.status_refresh_unconfirmed : R.string.status_mode_failed));
            links.releaseIfIdle();
        }
    }
    void setSpatial(boolean enabled) {
        if (state.capability(Controls.CAP_SPATIAL) != DeviceState.SUPPORTED || state.spatial == null) return;
        links.command(s -> { s.setSpatial(enabled); main.postDelayed(() -> {
            if (links.current(s)) links.command(Controls::querySpatial);
        }, 600); });
    }
    void setEqualizer(int id) {
        for (SoundPreset p : state.presets) if (p.id == id && (state.caps & (Controls.CAP_EQUALIZER | Controls.CAP_DEEPX)) != 0) {
            links.command(s -> { s.setEqualizer(id); main.postDelayed(() -> {
                if (links.current(s)) links.command(Controls::queryEqualizer);
            }, 600); });
            return;
        }
    }
    private void effects() {
        links.command(s -> {
            if ((s.potentialCaps() & Controls.CAP_SPATIAL) != 0) s.querySpatial();
            if ((s.potentialCaps() & (Controls.CAP_DEEPX | Controls.CAP_EQUALIZER)) != 0) s.queryEqualizer();
        });
    }
    private void poll() { if (foreground() && live()) { read(true, true); schedulePoll(); } }
    private void schedulePoll() {
        main.removeCallbacks(poll);
        if (foreground() && live()) main.postDelayed(poll, 60000);
    }
}

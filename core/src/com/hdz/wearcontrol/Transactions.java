package com.hdz.wearcontrol;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-actor transaction state. Only claim() additionally runs on the protocol actor. */
final class Transactions {
    interface Host {
        boolean live();
        int potential();
        int confirmed();
        int mode();
        boolean refreshPublicProfiles(Consumer<Boolean> completed);
        void read(boolean battery, boolean noise);
        void write(int mode, BooleanSupplier claim, Runnable flushed);
        void reopen();
        void later(Runnable work, long delay);
        void cancel(Runnable work);
        void changed(int mode, boolean active, boolean success);
    }
    static final long TIMEOUT = 12000L;
    private final Host host;
    private volatile Request active;
    private int unresolved = -1;
    private enum Phase { WAIT, QUEUED, WRITING, SENT }
    static final class Request {
        final int mode;
        volatile Phase phase = Phase.WAIT;
        boolean battery, noise, recovery, force, profilesOnly;
        Runnable timer;
        Request(int mode) { this.mode = mode; }
    }

    Transactions(Host host) { this.host = host; }
    boolean busy() { return active != null; }
    int pending() { return active == null ? -1 : active.mode; }
    synchronized Request receipt() { return active; }
    static boolean dispatched(Request request) { return request != null && request.phase == Phase.SENT; }

    void begin(int mode) {
        if (mode < -1 || mode > 2) return;
        Request next = new Request(mode);
        synchronized (this) {
            Request old = active;
            if (old != null && mode >= 0 && old.mode == mode) return;
            if (old != null) host.cancel(old.timer);
            active = next;
            next.force = unresolved >= 0;
        }
        host.changed(mode, true, false);
        next.timer = () -> timeout(next);
        if (host.live()) connected();
    }
    void connected() {
        Request r = active;
        if (r == null) return;
        host.cancel(r.timer);
        host.later(r.timer, TIMEOUT);
        if (r.mode < 0) {
            // Broadcast-only battery has no query. A public profile lookup is the entire refresh.
            r.profilesOnly = host.refreshPublicProfiles(found -> {
                if (active == r) finish(found);
            });
            if (r.profilesOnly) return;
        }
        if (r.mode >= 0 && (host.potential() & Controls.CAP_NOISE) == 0) { finish(false); return; }
        host.read(needsBattery(), needsNoise());
        progress(r, host.mode(), false);
    }
    private boolean needsBattery() { return (host.potential() & (Controls.CAP_BATTERY | Controls.CAP_BATTERY_SINGLE)) != 0; }
    private boolean needsNoise() { return (host.potential() & Controls.CAP_NOISE) != 0; }

    private void progress(Request r, int reported, boolean afterSend) {
        if (r != active) return;
        if (r.mode < 0) {
            if ((r.battery || r.noise) && (!needsBattery() || r.battery) && (!needsNoise() || r.noise)) finish(true);
            return;
        }
        if ((host.confirmed() & Controls.CAP_NOISE) == 0) return;
        if (r.mode == reported && (afterSend || r.phase == Phase.WAIT && !r.force)) {
            synchronized (this) { unresolved = -1; }
            finish(true);
        } else if (r.phase == Phase.WAIT) {
            r.phase = Phase.QUEUED;
            host.write(r.mode, () -> claim(r), () -> written(r));
        }
    }
    private synchronized boolean claim(Request r) {
        if (r != active || r.phase != Phase.QUEUED) return false;
        r.phase = Phase.WRITING;
        unresolved = r.mode;
        return true;
    }

    private synchronized void written(Request r) {
        if (r != active || r.phase != Phase.WRITING) return;
        r.phase = Phase.SENT;
        // A fast earbud may report before the stack delivers its write-completion callback.
        host.later(() -> { if (r == active && r.phase == Phase.SENT) host.read(false, true); }, 1000);
    }
    void battery(Request receipt) {
        if (receipt == null || receipt != active || receipt.profilesOnly) return;
        receipt.battery = true;
        if (receipt.mode < 0) progress(receipt, -1, false);
    }
    void noise(Request receipt, int mode, boolean afterSend) {
        if (receipt == null || receipt != active || receipt.profilesOnly || mode < -1 || mode > 2) return;
        receipt.noise = mode >= 0;
        progress(receipt, mode, afterSend);
    }
    private void timeout(Request r) {
        if (active != r) return;
        if (r.mode >= 0 || r.recovery || r.profilesOnly) { finish(false); return; }
        r.recovery = true;
        if (host.live() && (r.battery || r.noise)) {
            host.later(r.timer, TIMEOUT);
            host.read(needsBattery() && !r.battery, needsNoise() && !r.noise);
        } else host.reopen();
    }
    synchronized void linkEnded() {
        unresolved = -1;
        if (active != null) {
            host.cancel(active.timer);
            active.battery = active.noise = active.force = false;
            active.phase = Phase.WAIT;
        }
    }
    void finish(boolean success) {
        Request r;
        synchronized (this) { r = active; active = null; }
        if (r == null) return;
        host.cancel(r.timer);
        host.changed(r.mode, false, success);
    }
}

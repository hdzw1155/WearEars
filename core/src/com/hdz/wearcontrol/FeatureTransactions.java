package com.hdz.wearcontrol;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Per-lane confirmed-write transaction engine for extended device features.
 * Main-actor owned; only receipt(), dispatched(), claim(), and written() touch the protocol actor.
 */
final class FeatureTransactions {
    interface Host {
        boolean live();
        boolean writable(FeatureId id, int value);
        Integer currentValue(FeatureId id);
        void read(FeatureId id);
        void write(FeatureId id, int value, BooleanSupplier claim, Runnable flushed);
        void later(Runnable work, long delay);
        void cancel(Runnable work);
        void changed(FeatureId id, int value, boolean active, boolean success);
    }

    static final long TIMEOUT = 7000L;
    static final long FIND_TIMEOUT = 4000L;
    static final long FIND_RING_MAX_MS = 30000L;
    static final long READBACK_DELAY_MS = 1000L;
    private static final int WEAR_UNKNOWN = -1, WEAR_LEFT = 1, WEAR_RIGHT = 2, WEAR_BOTH = 3;

    private enum Phase { WAIT, QUEUED, WRITING, SENT }

    static final class Request {
        final FeatureId id;
        final int value;
        volatile Phase phase = Phase.WAIT;
        boolean force;
        Runnable timer;
        Runnable readback;

        Request(FeatureId id, int value) {
            this.id = id;
            this.value = value;
        }
    }

    private final Host host;
    private final EnumMap<FeatureId, Request> lanes = new EnumMap<>(FeatureId.class);
    private final EnumMap<FeatureId, Integer> unresolved = new EnumMap<>(FeatureId.class);
    private int wearStatus = WEAR_UNKNOWN;
    private boolean findRinging;
    private Runnable findAutoStop;

    FeatureTransactions(Host host) {
        this.host = host;
    }

    synchronized boolean busy() {
        return !lanes.isEmpty();
    }

    synchronized boolean busy(FeatureId id) {
        return id != null && lanes.containsKey(id);
    }

    synchronized int pending(FeatureId id) {
        if (id == null) return -1;
        Request r = lanes.get(id);
        return r == null ? -1 : r.value;
    }

    synchronized boolean isFindBudsRinging() {
        return findRinging;
    }

    synchronized Request receipt(FeatureId id) {
        return id == null ? null : lanes.get(id);
    }

    static boolean dispatched(Request request) {
        return request != null && request.phase == Phase.SENT;
    }

    boolean begin(FeatureId id, int value) {
        if (id == null) return false;
        if (id == FeatureId.FIND_MY_BUDS && value > 0 && anyBudInEar(wearStatus)) return false;
        if (host.live() && !host.writable(id, value)) return false;

        Request old;
        Request next = new Request(id, value);
        synchronized (this) {
            old = lanes.get(id);
            if (old != null && old.value == value) return false;
            lanes.put(id, next);
            next.force = unresolved.containsKey(id);
        }
        if (old != null) cancelTimers(old);

        if (id == FeatureId.SPATIAL_AUDIO && value == 0) {
            cancelLane(FeatureId.SPATIAL_SCENE);
            cancelLane(FeatureId.HEAD_TRACKING);
        }
        if (id == FeatureId.FIND_MY_BUDS) {
            if (value > 0) {
                findRinging = true;
            } else {
                stopFindAutoTimer();
                findRinging = false;
            }
        }

        host.changed(id, value, true, false);
        next.timer = () -> timeout(next);
        if (host.live()) startLane(next);
        return true;
    }

    void connected() {
        List<Request> snapshot;
        synchronized (this) {
            if (lanes.isEmpty()) return;
            snapshot = new ArrayList<>(lanes.values());
        }
        for (Request r : snapshot) {
            if (lanes.get(r.id) == r) startLane(r);
        }
    }

    private void startLane(Request r) {
        if (lanes.get(r.id) != r) return;
        if (!host.writable(r.id, r.value)) {
            finish(r.id, false);
            return;
        }
        if (r.id == FeatureId.FIND_MY_BUDS && r.value > 0) {
            if (anyBudInEar(wearStatus)) {
                finish(r.id, false);
                return;
            }
            armFindAutoTimer();
        }
        host.cancel(r.timer);
        host.later(r.timer, r.id == FeatureId.FIND_MY_BUDS ? FIND_TIMEOUT : TIMEOUT);
        progress(r, host.currentValue(r.id), false);
    }

    private void progress(Request r, Integer reported, boolean afterSend) {
        if (lanes.get(r.id) != r) return;
        boolean matchesCurrent = r.id != FeatureId.FIND_MY_BUDS && reported != null && reported == r.value;
        boolean matchesAfterSend = afterSend && reported != null && reported == r.value;
        if (matchesAfterSend || (matchesCurrent && r.phase == Phase.WAIT && !r.force)) {
            synchronized (this) { unresolved.remove(r.id); }
            finish(r.id, true);
        } else if (r.phase == Phase.WAIT) {
            r.phase = Phase.QUEUED;
            host.write(r.id, r.value, () -> claim(r), () -> written(r));
        }
    }

    private synchronized boolean claim(Request r) {
        if (lanes.get(r.id) != r || r.phase != Phase.QUEUED) return false;
        r.phase = Phase.WRITING;
        unresolved.put(r.id, r.value);
        return true;
    }

    private synchronized boolean isSent(Request r) {
        return lanes.get(r.id) == r && r.phase == Phase.SENT;
    }

    private void written(Request r) {
        Runnable rb;
        synchronized (this) {
            if (lanes.get(r.id) != r || r.phase != Phase.WRITING) return;
            r.phase = Phase.SENT;
            rb = () -> {
                if (isSent(r)) host.read(r.id);
            };
            r.readback = rb;
        }
        host.later(rb, READBACK_DELAY_MS);
    }

    void report(Request receipt, FeatureId id, int value, boolean afterSend) {
        if (id == FeatureId.WEAR_STATUS) onWearStatus(value);
        if (id == FeatureId.FIND_MY_BUDS && value == 0) onFindBudsStopped();
        if (receipt == null || id == null || lanes.get(id) != receipt) return;
        progress(receipt, value, afterSend);
    }

    void onTopModeBegin(int mode) {
        cancelLane(FeatureId.ANC_LEVEL);
        cancelLane(FeatureId.TRANS_MODE);
    }

    void onWearStatus(int status) {
        wearStatus = status;
        if (anyBudInEar(status) && (findRinging || pending(FeatureId.FIND_MY_BUDS) > 0)) {
            stopFindBuds();
        }
    }

    void onFindBudsStopped() {
        Request activeFind = receipt(FeatureId.FIND_MY_BUDS);
        if (activeFind == null || activeFind.value == 0) {
            stopFindAutoTimer();
            findRinging = false;
        }
    }

    void stopFindBuds() {
        Integer cur = host.currentValue(FeatureId.FIND_MY_BUDS);
        boolean shouldStop = findRinging || pending(FeatureId.FIND_MY_BUDS) > 0 || (cur != null && cur > 0);
        stopFindAutoTimer();
        findRinging = false;
        if (!shouldStop) return;
        Request activeFind = receipt(FeatureId.FIND_MY_BUDS);
        if ((cur == null || cur <= 0) && activeFind != null && activeFind.value > 0
                && activeFind.phase != Phase.WRITING && activeFind.phase != Phase.SENT) {
            cancelLane(FeatureId.FIND_MY_BUDS);
            return;
        }
        if (host.live() && host.writable(FeatureId.FIND_MY_BUDS, 0)) {
            begin(FeatureId.FIND_MY_BUDS, 0);
        } else {
            cancelLane(FeatureId.FIND_MY_BUDS);
        }
    }

    private void armFindAutoTimer() {
        stopFindAutoTimer();
        Runnable task = () -> {
            findAutoStop = null;
            stopFindBuds();
        };
        findAutoStop = task;
        host.later(task, FIND_RING_MAX_MS);
    }

    private void stopFindAutoTimer() {
        if (findAutoStop != null) {
            host.cancel(findAutoStop);
            findAutoStop = null;
        }
    }

    private void timeout(Request r) {
        if (lanes.get(r.id) != r) return;
        finish(r.id, false);
    }

    void abortQueued(FeatureId id, BooleanSupplier claim) {
        if (id != null && claim != null && claim.getAsBoolean()) {
            synchronized (this) { unresolved.remove(id); }
            finish(id, false);
        }
    }

    void linkEnded() {
        List<Request> active;
        synchronized (this) {
            unresolved.clear();
            active = new ArrayList<>(lanes.values());
            lanes.clear();
        }
        stopFindAutoTimer();
        findRinging = false;
        wearStatus = WEAR_UNKNOWN;
        for (Request r : active) {
            cancelTimers(r);
            host.changed(r.id, r.value, false, false);
        }
    }

    private void cancelLane(FeatureId id) {
        Request r;
        synchronized (this) {
            r = lanes.remove(id);
            unresolved.remove(id);
        }
        if (r == null) return;
        cancelTimers(r);
        host.changed(r.id, r.value, false, false);
    }

    private void finish(FeatureId id, boolean success) {
        Request r;
        synchronized (this) {
            if (!lanes.containsKey(id)) return;
            r = lanes.remove(id);
        }
        if (r == null) return;
        cancelTimers(r);
        if (id == FeatureId.FIND_MY_BUDS && r.value > 0 && !success && r.phase != Phase.SENT) {
            stopFindAutoTimer();
            findRinging = false;
        }
        host.changed(r.id, r.value, false, success);
    }

    private void cancelTimers(Request r) {
        if (r.timer != null) host.cancel(r.timer);
        if (r.readback != null) host.cancel(r.readback);
    }

    private static boolean anyBudInEar(int status) {
        return status == WEAR_LEFT || status == WEAR_RIGHT || status == WEAR_BOTH;
    }
}

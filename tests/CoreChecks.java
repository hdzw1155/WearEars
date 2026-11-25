package com.hdz.wearcontrol;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class CoreChecks {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static final class Clock {
        long now;
        final TreeMap<Long, List<Runnable>> waiting = new TreeMap<>();
        void after(Runnable task, long ms) { waiting.computeIfAbsent(now + ms, key -> new ArrayList<>()).add(task); }
        void cancel(Runnable task) { for (List<Runnable> jobs : waiting.values()) jobs.removeIf(r -> r == task); }
        void tick(long ms) {
            long until = now + ms;
            while (!waiting.isEmpty() && waiting.firstKey() <= until) {
                now = waiting.firstKey();
                for (Runnable r : waiting.pollFirstEntry().getValue()) r.run();
            }
            now = until;
        }
    }
    static final class Host implements Transactions.Host {
        final Clock clock = new Clock();
        BooleanSupplier claim;
        Runnable flushed;
        boolean publicProfiles;
        Consumer<Boolean> profileResult;
        int caps = 2, mode = -1, target = -1, reopen, results, writes, reads;
        boolean success, batt, noise, connected = true;
        public boolean live() { return connected; }
        public int potential() { return publicProfiles ? Controls.CAP_BATTERY_SINGLE : 3; }
        public int confirmed() { return caps; }
        public int mode() { return mode; }
        public boolean refreshPublicProfiles(Consumer<Boolean> completed) {
            if (!publicProfiles) return false;
            profileResult = completed;
            return true;
        }
        public void read(boolean b, boolean n) { batt = b; noise = n; reads++; }
        public void write(int m, BooleanSupplier valid, Runnable done) { flushed = done; target = m; claim = valid; writes++; }
        public void reopen() { reopen++; }
        public void later(Runnable r, long ms) { clock.after(r, ms); }
        public void cancel(Runnable r) { clock.cancel(r); }
        public void changed(int m, boolean active, boolean ok) { if (!active) { results++; success = ok; } }
    }
    static void operations() {
        Host h = new Host();
        Transactions tx = new Transactions(h);
        tx.begin(1);
        check(tx.busy() && h.writes == 0, "wait for capability");
        h.caps = 3; h.mode = 1;
        tx.noise(tx.receipt(), 1, false);
        check(!tx.busy() && h.success && h.writes == 0, "target is the first confirmed mode");
        tx.begin(0);
        BooleanSupplier old = h.claim;
        Transactions.Request previous = tx.receipt();
        tx.begin(2);
        check(!old.getAsBoolean() && h.target == 2 && h.claim.getAsBoolean(), "supersede queued intent");
        check(!Transactions.dispatched(tx.receipt()), "queued write cannot confirm before physical completion");
        tx.noise(previous, 2, true);
        tx.battery(tx.receipt());
        tx.noise(tx.receipt(), 2, false);
        check(tx.busy(), "old transaction, battery and pre-dispatch report cannot confirm");
        tx.begin(1);
        check(h.target == 1 && h.claim.getAsBoolean(), "countermand an already issued write even if display matches");
        h.flushed.run();
        check(Transactions.dispatched(tx.receipt()), "physical completion opens report fence");
        tx.noise(tx.receipt(), 2, true);
        check(tx.busy(), "old target report does not settle new intent");
        tx.noise(tx.receipt(), 1, true);
        check(!tx.busy() && h.success, "matching evidence settles");
        tx.begin(-1);
        tx.battery(tx.receipt());
        h.clock.tick(Transactions.TIMEOUT);
        check(tx.busy() && h.noise && !h.batt && h.reopen == 0, "repair only missing field on live link");
        tx.noise(tx.receipt(), 2, false);
        check(!tx.busy() && h.success, "partial refresh completes on same link");
        tx.begin(-1); h.clock.tick(Transactions.TIMEOUT);
        check(h.reopen == 1 && tx.busy(), "empty refresh gets one recovery");
        tx.connected();
        h.clock.tick(Transactions.TIMEOUT);
        check(!tx.busy() && !h.success, "bounded deadline");
        h.connected = false;
        tx.begin(2);
        h.clock.tick(60000);
        check(tx.busy() && h.reopen == 1, "connection establishment owns its own deadline");
        h.connected = true; tx.connected();
        h.clock.tick(Transactions.TIMEOUT);
        check(!tx.busy() && !h.success, "control deadline starts at protocol readiness");
    }
    static void standardRefresh() {
        Host h = new Host();
        h.publicProfiles = true;
        Transactions tx = new Transactions(h);
        tx.begin(-1);
        check(tx.busy() && h.profileResult != null && h.reads == 0, "standard refresh queries public profiles only");
        tx.battery(tx.receipt());
        check(tx.busy(), "asynchronous system battery cannot settle a profile lookup");
        Consumer<Boolean> previous = h.profileResult;
        tx.begin(-1);
        previous.accept(false);
        check(tx.busy() && h.results == 0, "old profile result cannot settle the next refresh");
        h.profileResult.accept(true);
        h.clock.tick(Transactions.TIMEOUT * 2);
        check(!tx.busy() && h.success && h.reopen == 0 && h.results == 1,
                "unchanged or unknown battery does not restart a healthy public link");
        tx.begin(-1);
        h.profileResult.accept(false);
        check(!tx.busy() && !h.success && h.reopen == 0, "unconfirmed profile refresh preserves the session");
        tx.begin(-1);
        h.clock.tick(Transactions.TIMEOUT);
        check(!tx.busy() && !h.success && h.reopen == 0, "stalled public lookup does not force a reconnect");
    }

    static void selection() {
        Selection.Peer a = new Selection.Peer("A", true, false, true, false, false);
        Selection.Peer b = new Selection.Peer("B", true, false, true, false, false);
        Selection.Peer c = new Selection.Peer("C", true, false, true, true, true);
        Selection.Peer d = new Selection.Peer("D", true, false, true, true, true);
        Selection.Peer phone = new Selection.Peer("P", true, true, true, true, true);
        for (List<Selection.Peer> order : Arrays.asList(Arrays.asList(a,b),Arrays.asList(b,a),Arrays.asList(c,d))) {
            check(Selection.choose("", true, order).result == Selection.Result.AMBIGUOUS, "equal ranks");
        }
        check("C".equals(Selection.choose("", true, Arrays.asList(a,phone,c,b)).address), "unique top tier");
        check("B".equals(Selection.choose("B", false, Arrays.asList(c,b)).address), "pin fixed across partial profiles");
        check(Selection.choose("P", true, Arrays.asList(c,phone)).result == Selection.Result.PIN_UNAVAILABLE, "exclude phone");
        check(Selection.choose("", false, Arrays.asList(c)).result == Selection.Result.INCOMPLETE, "incomplete known");
        check(Selection.choose("", false, Collections.emptyList()).result == Selection.Result.INCOMPLETE, "incomplete empty");
        check(Selection.choose("", true, Arrays.asList(a)).address.equals("A"), "single unknown");
    }
    static void gatt() {
        Clock clock = new Clock();
        List<Integer> sizes = new ArrayList<>();
        List<String> result = new ArrayList<>();
        GattPump p = new GattPump(new GattPump.Port() {
            public boolean start(byte[] data) { sizes.add(data.length); return true; }
            public void later(Runnable r, long delay) { clock.after(r, delay); }
            public void cancel(Runnable r) { clock.cancel(r); }
            public void fail(String why) { result.add(why); }
        }, 30, true);
        p.open(); p.send(new byte[41], () -> result.add("done")); p.send(new byte[2], null);
        clock.tick(200);
        check(sizes.equals(Arrays.asList(20)), "slow Android callback holds the next operation");
        p.acknowledged(true); clock.tick(29);
        check(sizes.size() == 1, "pacing in addition to callback");
        clock.tick(1); p.acknowledged(true); clock.tick(30); p.acknowledged(true);
        check(sizes.equals(Arrays.asList(20,20,1)) && result.isEmpty(), "tail pacing");
        clock.tick(30);
        check(sizes.equals(Arrays.asList(20,20,1,2)) && result.equals(Arrays.asList("done")), "next frame after tail");
        clock.tick(4000);
        check(result.size() == 2, "missing callback fails session");
        p.acknowledged(true); clock.tick(10000);
        check(result.size() == 2, "late callback cannot resurrect transport");
    }
    public static void main(String[] args) {
        standardRefresh();
        selection(); operations(); gatt();
        check(DeviceState.lowComponent(new int[]{255, 0, 17}, 2) == 2, "ignore missing and charging values");
        check(DeviceState.lowComponent(new int[]{-1, 21, 20}, -1) == 2, "20 percent boundary");
        check(DeviceState.lowComponent(new int[]{0, 255, -1}, -1) == 0, "valid empty battery");
        check(!Consent.current(0,0) && !Consent.current(16,15) && Consent.current(16,16), "version gate");
        check(StyleChoice.resolve(0)==4 && StyleChoice.resolve(1)==5 && StyleChoice.resolve(2)==6 &&
                StyleChoice.resolve(7)==3 && StyleChoice.resolve(100)==4 && StyleChoice.tile(-1)==-1, "style migration");
        System.out.println("PASS " + checks + " core checks");
    }
}

package com.hdz.wearcontrol;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Pure-JVM unit tests for FeatureDescriptor, OptionItem, DeviceState, WireDriver bridges, and FeatureTransactions. */
public final class FeatureEngineChecks {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static final class Clock {
        long now;
        final TreeMap<Long, List<Runnable>> waiting = new TreeMap<>();

        void after(Runnable task, long ms) {
            waiting.computeIfAbsent(now + ms, k -> new ArrayList<>()).add(task);
        }

        void cancel(Runnable task) {
            for (List<Runnable> jobs : waiting.values()) jobs.removeIf(r -> r == task);
        }

        void tick(long ms) {
            long until = now + ms;
            while (!waiting.isEmpty() && waiting.firstKey() <= until) {
                now = waiting.firstKey();
                for (Runnable r : new ArrayList<>(waiting.pollFirstEntry().getValue())) r.run();
            }
            now = until;
        }
    }

    private static final class TxHost implements FeatureTransactions.Host {
        final Clock clock = new Clock();
        final EnumMap<FeatureId, Integer> values = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, Boolean> writables = new EnumMap<>(FeatureId.class);
        final List<FeatureId> reads = new ArrayList<>();
        final List<FeatureId> writeIds = new ArrayList<>();
        final List<Integer> writeVals = new ArrayList<>();
        final List<BooleanSupplier> claims = new ArrayList<>();
        final List<Runnable> flushes = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        boolean connected = true;

        @Override public boolean live() { return connected; }
        @Override public boolean writable(FeatureId id, int value) {
            Boolean allow = writables.get(id);
            return connected && (allow == null || allow);
        }
        @Override public Integer currentValue(FeatureId id) { return values.get(id); }
        @Override public void read(FeatureId id) { reads.add(id); }
        @Override public void write(FeatureId id, int value, BooleanSupplier claim, Runnable flushed) {
            writeIds.add(id);
            writeVals.add(value);
            claims.add(claim);
            flushes.add(flushed);
        }
        @Override public void later(Runnable work, long delay) { clock.after(work, delay); }
        @Override public void cancel(Runnable work) { clock.cancel(work); }
        @Override public void changed(FeatureId id, int value, boolean active, boolean success) {
            changes.add(id.name() + ":" + value + ":" + active + ":" + success);
        }
    }

    private static void optionAndDescriptorContracts() {
        OptionItem[] eq = OptionItem.fromPresets(SoundPreset.vivo());
        check(eq.length == 5 && eq[0].id == 0 && eq[4].id == 5
                && "听书".equals(eq[4].chinese) && "Audiobook".equals(eq[4].english)
                && eq[4].role == OptionItem.ROLE_DEFAULT,
                "OptionItem.fromPresets preserves SoundPreset IDs and labels");
        check(OptionItem.fromPresets(null).length == 0 && OptionItem.fromPresets(new SoundPreset[0]).length == 0,
                "OptionItem.fromPresets handles null/empty safely");

        OptionItem[] ancOpts = {
                new OptionItem(0, "轻度", "Light", OptionItem.ROLE_DEFAULT),
                new OptionItem(1, "深度", "Deep", OptionItem.ROLE_DEFAULT),
                new OptionItem(2, "自适应", "Adaptive", OptionItem.ROLE_ADAPTIVE),
                new OptionItem(3, "抗风噪", "Wind", OptionItem.ROLE_WIND)
        };
        FeatureDescriptor anc = FeatureDescriptor.choice(
                FeatureId.ANC_LEVEL, Controls.VERIF_REAL, FeatureDescriptor.SAFETY_NONE,
                1 << Controls.MODE_ANC, false, ancOpts, "仅降噪模式", "ANC only");
        check(anc.kind == FeatureDescriptor.Kind.CHOICE && anc.verif == Controls.VERIF_REAL
                && anc.allowsNoiseMode(Controls.MODE_ANC)
                && !anc.allowsNoiseMode(Controls.MODE_OFF)
                && !anc.allowsNoiseMode(Controls.MODE_TRANS),
                "ANC_LEVEL parentModeMask gates mode visibility");
        check(anc.isValidValue(3) && !anc.isValidValue(99)
                && anc.findOption(3).role == OptionItem.ROLE_WIND
                && "ANC only".equals(anc.limit(true)) && "仅降噪模式".equals(anc.limit(false)),
                "CHOICE validation, option role lookup, and localized limits");
        ancOpts[0] = null;
        check(anc.options[0] != null && anc.options.length == 4, "FeatureDescriptor defensively copies options");

        FeatureDescriptor wind = FeatureDescriptor.toggle(
                FeatureId.WIND_REDUCTION, Controls.VERIF_UNTESTED, FeatureDescriptor.SAFETY_AUDIO_WARN,
                (1 << Controls.MODE_ANC) | (1 << Controls.MODE_TRANS), true);
        check(wind.isValidValue(0) && wind.isValidValue(1) && !wind.isValidValue(2)
                && wind.devOnly && wind.safety == FeatureDescriptor.SAFETY_AUDIO_WARN
                && wind.allowsNoiseMode(Controls.MODE_ANC) && wind.allowsNoiseMode(Controls.MODE_TRANS)
                && !wind.allowsNoiseMode(Controls.MODE_OFF),
                "TOGGLE descriptor validates 0/1 and multi-mode mask");

        FeatureDescriptor slider = FeatureDescriptor.slider(
                FeatureId.ANC_LEVEL, Controls.VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
                1 << Controls.MODE_ANC, true, 10, 1);
        check(slider.minValue == 1 && slider.maxValue == 10
                && slider.isValidValue(1) && slider.isValidValue(10) && !slider.isValidValue(0),
                "SLIDER normalizes min/max bounds and validates range");

        FeatureDescriptor wear = FeatureDescriptor.readOnly(FeatureId.WEAR_STATUS, Controls.VERIF_REAL, false);
        check(wear.kind == FeatureDescriptor.Kind.READ_ONLY && !wear.isValidValue(0) && !wear.isValidValue(1),
                "READ_ONLY descriptor rejects writes");

        FeatureDescriptor find = FeatureDescriptor.findBuds(Controls.VERIF_UNTESTED, false);
        check(find.id == FeatureId.FIND_MY_BUDS && find.kind == FeatureDescriptor.Kind.ACTION_TARGET
                && find.safety == FeatureDescriptor.SAFETY_FIND_BUDS
                && find.isValidValue(0) && find.isValidValue(1) && find.isValidValue(2) && find.isValidValue(3)
                && !find.isValidValue(4),
                "findBuds factory sets SAFETY_FIND_BUDS and validates targets 0..3");
    }

    private static void deviceStateFeatureMaps() {
        DeviceState s = new DeviceState();
        s.begin("11:22:33:44:55:66", "Test Buds", 0, 0);
        s.connected = true;
        s.limitation = "standard";

        FeatureDescriptor wearSpec = FeatureDescriptor.readOnly(FeatureId.WEAR_STATUS, Controls.VERIF_REAL, false);
        FeatureDescriptor lowLatSpec = FeatureDescriptor.toggle(FeatureId.LOW_LATENCY, Controls.VERIF_UNTESTED, false);
        FeatureDescriptor findSpec = FeatureDescriptor.findBuds(Controls.VERIF_UNTESTED, false);
        FeatureDescriptor ancLevelSpec = FeatureDescriptor.choice(
                FeatureId.ANC_LEVEL, Controls.VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
                1 << Controls.MODE_ANC, false,
                new OptionItem(0, "轻度", "Light"), new OptionItem(1, "深度", "Deep"));
        FeatureDescriptor eqSpec = FeatureDescriptor.choice(
                FeatureId.EQUALIZER, Controls.VERIF_UNTESTED, false,
                new OptionItem(0, "标准", "Balanced"), new OptionItem(1, "低音", "Bass"));

        s.featureSpec(wearSpec);
        s.featureSpec(lowLatSpec);
        s.featureSpec(findSpec);
        s.featureSpec(ancLevelSpec);
        s.featureSpec(eqSpec);
        check(!s.isFeatureWritable(FeatureId.WEAR_STATUS), "READ_ONLY feature is never writable");
        check(!s.isFeatureWritable(FeatureId.LOW_LATENCY), "TOGGLE without initial value is not writable yet");
        check(s.isFeatureWritable(FeatureId.FIND_MY_BUDS), "ACTION_TARGET FIND_MY_BUDS is writable once spec confirmed");
        check(!s.isFeatureWritable(FeatureId.ANC_LEVEL), "ANC_LEVEL requires CAP_NOISE and MODE_ANC");
        s.capabilities(Controls.CAP_NOISE, Controls.CAP_NOISE);
        s.mode = Controls.MODE_OFF;
        check(!s.isFeatureWritable(FeatureId.ANC_LEVEL), "ANC_LEVEL not writable while MODE_OFF");
        s.mode = Controls.MODE_ANC;
        check(s.isFeatureWritable(FeatureId.ANC_LEVEL),
                "CHOICE ANC_LEVEL is clickable/writable in MODE_ANC even before initial sub-level value arrives");
        check(s.isFeatureWritable(FeatureId.EQUALIZER),
                "CHOICE EQUALIZER is clickable/writable once spec confirmed");

        s.featureValue(FeatureId.LOW_LATENCY, 1);
        check(s.isFeatureWritable(FeatureId.LOW_LATENCY)
                && Integer.valueOf(1).equals(s.featureValue(FeatureId.LOW_LATENCY))
                && s.featureRevision(FeatureId.LOW_LATENCY) == 1,
                "confirmed value unlocks writability and increments revision");

        check(!s.isAnyBudInEar() && s.wearStatus == DeviceState.WEAR_UNKNOWN, "initial wear status is unknown");
        s.featureValue(FeatureId.WEAR_STATUS, DeviceState.WEAR_NONE);
        check(!s.isAnyBudInEar() && !s.isTargetInEar(3), "WEAR_NONE means not in ear");
        s.featureValue(FeatureId.WEAR_STATUS, DeviceState.WEAR_LEFT);
        check(s.isAnyBudInEar() && s.isTargetInEar(1) && !s.isTargetInEar(2) && s.isTargetInEar(3),
                "WEAR_LEFT flags left and both targets only");
        s.featureValue(FeatureId.WEAR_STATUS, DeviceState.WEAR_RIGHT);
        check(s.isAnyBudInEar() && !s.isTargetInEar(1) && s.isTargetInEar(2) && s.isTargetInEar(3),
                "WEAR_RIGHT flags right and both targets only");
        s.featureValue(FeatureId.WEAR_STATUS, DeviceState.WEAR_BOTH);
        check(s.isAnyBudInEar() && s.isTargetInEar(1) && s.isTargetInEar(2) && s.isTargetInEar(3),
                "WEAR_BOTH flags all ring targets");

        s.featureValue(FeatureId.SPATIAL_AUDIO, 1);
        check(Boolean.TRUE.equals(s.spatial) && s.spatialRevision == 1, "SPATIAL_AUDIO syncs DeviceState.spatial");
        s.featureValue(FeatureId.EQUALIZER, 5);
        check(Integer.valueOf(5).equals(s.equalizer) && s.equalizerRevision == 1, "EQUALIZER syncs DeviceState.equalizer");
        s.featureText(FeatureId.FIRMWARE_INFO, "1.2.3-rc1");
        check("1.2.3-rc1".equals(s.firmware) && "1.2.3-rc1".equals(s.featureText(FeatureId.FIRMWARE_INFO)),
                "FIRMWARE_INFO syncs DeviceState.firmware");

        s.disconnect();
        check(!s.connected && s.features.isEmpty() && s.featureValues.isEmpty()
                && s.featureRevisions.isEmpty() && s.featureTexts.isEmpty()
                && s.wearStatus == DeviceState.WEAR_UNKNOWN && s.firmware.isEmpty() && s.limitation.isEmpty()
                && s.featureValue(FeatureId.LOW_LATENCY) == null && !s.isFeatureWritable(FeatureId.FIND_MY_BUDS),
                "disconnect clears all session-scoped feature maps and fields");

        s.connected = true;
        s.featureSpec(lowLatSpec);
        s.featureValue(FeatureId.LOW_LATENCY, 0);
        s.featureText(FeatureId.FIRMWARE_INFO, "2.0");
        s.begin("AA:BB:CC:DD:EE:FF", "Other", 0, 0);
        check(s.features.isEmpty() && s.featureValues.isEmpty() && s.firmware.isEmpty()
                && s.wearStatus == DeviceState.WEAR_UNKNOWN,
                "begin clears previous session feature maps");
    }

    private static void wireDriverBridgeAndHelpers() {
        final List<String> calls = new ArrayList<>();
        final List<FeatureDescriptor> specs = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Runnable> portFlushes = new ArrayList<>();
        final boolean[] flushedFlags = new boolean[2];

        WireDriver driver = new WireDriver() {
            @Override protected UUID[] services(UUID[] advertised) { return new UUID[0]; }
            @Override protected void opened() { ready(); }
            @Override protected void received(byte[] bytes) { }
            @Override public void queryBattery() { }
            @Override public void queryNoise() { }
            @Override public void changeNoise(int mode, Runnable flushed) { }
            @Override public int caps() { return CAP_SPATIAL | CAP_EQUALIZER; }
            @Override public String brandName() { return "BridgeTest"; }
            @Override public void querySpatial() { calls.add("querySpatial"); }
            @Override public void setSpatial(boolean on) {
                calls.add("setSpatial:" + on);
                writeThen(new byte[]{0x01, (byte) (on ? 1 : 0)}, null);
            }
            @Override public void queryEqualizer() { calls.add("queryEqualizer"); }
            @Override public void setEqualizer(int preset) { calls.add("setEqualizer:" + preset); }
            void emitSamples() {
                FeatureDescriptor spec = FeatureDescriptor.toggle(FeatureId.LOW_LATENCY, VERIF_REAL, false);
                confirmFeature(spec, 1);
                confirmFeature(FeatureDescriptor.readOnly(FeatureId.WEAR_STATUS, VERIF_REAL, false));
                reportFeature(FeatureId.WEAR_STATUS, DeviceState.WEAR_NONE);
                reportFeatureText(FeatureId.FIRMWARE_INFO, "v3.1");
            }
        };
        driver.bind("Test", new WireDriver.Port() {
            @Override public void send(byte[] bytes, Runnable flushed) {
                calls.add("portSend:" + bytes.length);
                portFlushes.add(flushed);
            }
            @Override public void after(Runnable work, long delay) { }
            @Override public void cancel(Runnable work) { }
            @Override public void useBle(UUID s, UUID n, UUID w, long d, long t) { }
            @Override public void fail(String message) { }
            @Override public long millis() { return 0; }
            @Override public void trace(String tag, String message) { }
        }, new Controls.Listener() {
            @Override public void onConnected(String deviceName) { }
            @Override public void onDisconnected(String reason) { }
            @Override public void onError(String msg) { }
            @Override public void onBattery(int l, int r, int b, int c) { }
            @Override public void onNoiseMode(int mode) { }
            @Override public void onSpatial(Boolean state) { }
            @Override public void onDeepX(Integer mode) { }
            @Override public void onFeatureSpec(FeatureDescriptor spec) { specs.add(spec); }
            @Override public void onFeatureValue(FeatureId id, int value) { values.add(id + "=" + value); }
            @Override public void onFeatureText(FeatureId id, String text) { texts.add(id + "=" + text); }
        });
        driver.onOpen();

        check(driver.featureDescriptors().length == 0, "default featureDescriptors is empty");
        driver.queryFeature(FeatureId.SPATIAL_AUDIO);
        driver.queryFeature(FeatureId.EQUALIZER);
        check(calls.equals(Arrays.asList("querySpatial", "queryEqualizer")),
                "WireDriver queryFeature bridges spatial and equalizer");

        driver.writeFeature(FeatureId.SPATIAL_AUDIO, 1, () -> flushedFlags[0] = true);
        check(calls.contains("setSpatial:true") && calls.contains("portSend:2")
                && portFlushes.size() == 1 && !flushedFlags[0],
                "bridged writeFeature attaches flushed to legacy writeThen transport frame");
        portFlushes.get(0).run();
        check(flushedFlags[0], "port flush triggers bridged writeFeature callback");

        driver.writeFeature(FeatureId.EQUALIZER, 5, () -> flushedFlags[1] = true);
        check(calls.contains("setEqualizer:5") && !flushedFlags[1],
                "bridged writeFeature does not fabricate flushed when legacy override sends no frame");

        try {
            driver.getClass().getDeclaredMethod("emitSamples").invoke(driver);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        check(specs.size() == 2 && values.equals(Arrays.asList("LOW_LATENCY=1", "WEAR_STATUS=0"))
                && texts.equals(Collections.singletonList("FIRMWARE_INFO=v3.1")),
                "WireDriver protected confirm/report helpers emit spec, value, and text events");
    }

    private static void transactionCoalescingAndReadback() {
        TxHost h = new TxHost();
        FeatureTransactions tx = new FeatureTransactions(h);
        h.values.put(FeatureId.ANC_LEVEL, 0);
        h.values.put(FeatureId.LOW_LATENCY, 0);

        check(tx.begin(FeatureId.ANC_LEVEL, 1), "start ANC_LEVEL=1");
        check(!tx.begin(FeatureId.ANC_LEVEL, 1), "duplicate pending tap ignored");
        BooleanSupplier claim1 = h.claims.get(0);
        FeatureTransactions.Request req1 = tx.receipt(FeatureId.ANC_LEVEL);

        check(tx.begin(FeatureId.ANC_LEVEL, 2), "coalesce to ANC_LEVEL=2");
        check(!claim1.getAsBoolean(), "superseded queued claim returns false");
        check(tx.pending(FeatureId.ANC_LEVEL) == 2 && h.writeIds.size() == 2, "new write queued");

        // Independent lane does not clobber ANC_LEVEL
        check(tx.begin(FeatureId.LOW_LATENCY, 1), "concurrent LOW_LATENCY lane allowed");
        check(tx.pending(FeatureId.ANC_LEVEL) == 2 && tx.pending(FeatureId.LOW_LATENCY) == 1,
                "independent lanes stay isolated");

        BooleanSupplier claim2 = h.claims.get(1);
        Runnable flush2 = h.flushes.get(1);
        FeatureTransactions.Request req2 = tx.receipt(FeatureId.ANC_LEVEL);
        check(claim2.getAsBoolean(), "latest ANC_LEVEL claim succeeds");
        check(!FeatureTransactions.dispatched(req2), "not dispatched before flush");

        // Pre-flush report and stale req1 report cannot settle req2
        tx.report(req1, FeatureId.ANC_LEVEL, 2, true);
        tx.report(req2, FeatureId.ANC_LEVEL, 2, false);
        check(tx.busy(FeatureId.ANC_LEVEL), "stale request and pre-dispatch echo cannot settle");

        // Countermand back to displayed value (0) while write 2 is already claimed (unresolved)
        check(tx.begin(FeatureId.ANC_LEVEL, 0), "countermand back to displayed value after claim");
        check(h.writeIds.size() == 4 && h.writeVals.get(3) == 0,
                "unresolved guard forces countermand write even though current value is 0");
        flush2.run();
        check(h.claims.get(3).getAsBoolean(), "countermand claim succeeds");
        FeatureTransactions.Request req0 = tx.receipt(FeatureId.ANC_LEVEL);
        tx.report(req0, FeatureId.ANC_LEVEL, 0, false);
        check(tx.busy(FeatureId.ANC_LEVEL), "pre-dispatch report cannot settle countermand");

        h.flushes.get(3).run();
        check(FeatureTransactions.dispatched(req0), "flush marks dispatched");
        h.clock.tick(999);
        check(h.reads.isEmpty(), "readback waits full 1000ms");
        h.clock.tick(1);
        check(h.reads.equals(Collections.singletonList(FeatureId.ANC_LEVEL)),
                "post-flush 1000ms delayed readback fires queryFeature");

        tx.report(req0, FeatureId.ANC_LEVEL, 2, true);
        check(tx.busy(FeatureId.ANC_LEVEL), "mismatching value keeps lane open");
        tx.report(req0, FeatureId.ANC_LEVEL, 0, true);
        check(!tx.busy(FeatureId.ANC_LEVEL), "matching post-flush report settles ANC_LEVEL");

        // LOW_LATENCY was never flushed -> times out at 7000ms (we already ticked 1000ms)
        check(tx.busy(FeatureId.LOW_LATENCY), "LOW_LATENCY still active before 7000ms");
        h.clock.tick(FeatureTransactions.TIMEOUT - 1001);
        check(tx.busy(FeatureId.LOW_LATENCY), "LOW_LATENCY does not time out early");
        h.clock.tick(1);
        check(!tx.busy(FeatureId.LOW_LATENCY) && h.changes.contains("LOW_LATENCY:1:false:false"),
                "LOW_LATENCY times out at 7000ms and notifies failure");
    }

    private static void couplingAbortAndLinkEnded() {
        TxHost h = new TxHost();
        FeatureTransactions tx = new FeatureTransactions(h);
        h.values.put(FeatureId.ANC_LEVEL, 0);
        h.values.put(FeatureId.TRANS_MODE, 0);
        h.values.put(FeatureId.LOW_LATENCY, 0);
        h.values.put(FeatureId.SPATIAL_AUDIO, 1);
        h.values.put(FeatureId.SPATIAL_SCENE, 0);
        h.values.put(FeatureId.HEAD_TRACKING, 0);

        tx.begin(FeatureId.ANC_LEVEL, 1);
        tx.begin(FeatureId.TRANS_MODE, 1);
        tx.begin(FeatureId.LOW_LATENCY, 1);
        BooleanSupplier ancClaim = h.claims.get(0);
        BooleanSupplier transClaim = h.claims.get(1);

        tx.onTopModeBegin(Controls.MODE_OFF);
        check(!tx.busy(FeatureId.ANC_LEVEL) && !tx.busy(FeatureId.TRANS_MODE)
                && !ancClaim.getAsBoolean() && !transClaim.getAsBoolean()
                && tx.busy(FeatureId.LOW_LATENCY),
                "NOISE_MODE begin cancels pending ANC_LEVEL and TRANS_MODE without touching LOW_LATENCY");

        tx.begin(FeatureId.SPATIAL_SCENE, 2);
        tx.begin(FeatureId.HEAD_TRACKING, 1);
        BooleanSupplier sceneClaim = h.claims.get(3);
        BooleanSupplier headClaim = h.claims.get(4);
        tx.begin(FeatureId.SPATIAL_AUDIO, 0);
        check(!tx.busy(FeatureId.SPATIAL_SCENE) && !tx.busy(FeatureId.HEAD_TRACKING)
                && !sceneClaim.getAsBoolean() && !headClaim.getAsBoolean()
                && tx.busy(FeatureId.SPATIAL_AUDIO),
                "SPATIAL_AUDIO=0 cancels pending SPATIAL_SCENE and HEAD_TRACKING");

        BooleanSupplier spatialClaim = h.claims.get(5);
        tx.abortQueued(FeatureId.SPATIAL_AUDIO, spatialClaim);
        check(!tx.busy(FeatureId.SPATIAL_AUDIO) && h.changes.contains("SPATIAL_AUDIO:0:false:false"),
                "abortQueued clears queued lane as failure");

        tx.linkEnded();
        check(!tx.busy(), "linkEnded clears remaining lanes");
    }

    private static void findBudsSafetyContracts() {
        TxHost h = new TxHost();
        FeatureTransactions tx = new FeatureTransactions(h);

        // Hard-block starting while any bud is in ear
        tx.onWearStatus(DeviceState.WEAR_LEFT);
        check(!tx.begin(FeatureId.FIND_MY_BUDS, 1) && h.writeIds.isEmpty(),
                "Find My Buds refuses start when bud is already in ear");

        tx.onWearStatus(DeviceState.WEAR_NONE);
        check(tx.begin(FeatureId.FIND_MY_BUDS, 3) && tx.isFindBudsRinging(),
                "Find My Buds starts when not worn");
        check(h.claims.get(0).getAsBoolean(), "claim start ring");
        h.flushes.get(0).run();
        tx.report(tx.receipt(FeatureId.FIND_MY_BUDS), FeatureId.FIND_MY_BUDS, 3, true);
        h.values.put(FeatureId.FIND_MY_BUDS, 3);
        check(!tx.busy(FeatureId.FIND_MY_BUDS) && tx.isFindBudsRinging(),
                "confirmed start finishes write lane while keeping ringing state active");

        // 30s auto-stop timer
        h.clock.tick(FeatureTransactions.FIND_RING_MAX_MS - 1);
        check(h.writeIds.size() == 1, "does not auto-stop before 30000ms");
        h.clock.tick(1);
        check(h.writeIds.size() == 2 && h.writeIds.get(1) == FeatureId.FIND_MY_BUDS
                && h.writeVals.get(1) == 0 && !tx.isFindBudsRinging(),
                "30000ms timer automatically triggers FIND_MY_BUDS=0");
        h.claims.get(1).getAsBoolean();
        h.flushes.get(1).run();
        tx.report(tx.receipt(FeatureId.FIND_MY_BUDS), FeatureId.FIND_MY_BUDS, 0, true);
        h.values.put(FeatureId.FIND_MY_BUDS, 0);
        tx.stopFindBuds();
        check(h.writeIds.size() == 2, "stopFindBuds is a no-op once already stopped");

        // Live wear kill-switch while ringing
        check(tx.begin(FeatureId.FIND_MY_BUDS, 2) && tx.isFindBudsRinging(), "start right bud ring");
        h.claims.get(2).getAsBoolean();
        h.flushes.get(2).run();
        tx.report(tx.receipt(FeatureId.FIND_MY_BUDS), FeatureId.FIND_MY_BUDS, 2, true);
        h.values.put(FeatureId.FIND_MY_BUDS, 2);

        tx.onWearStatus(DeviceState.WEAR_UNKNOWN);
        check(tx.isFindBudsRinging() && h.writeIds.size() == 3, "WEAR_UNKNOWN does not kill active ring");
        tx.report(null, FeatureId.WEAR_STATUS, DeviceState.WEAR_RIGHT, false);
        check(!tx.isFindBudsRinging() && h.writeIds.size() == 4
                && h.writeIds.get(3) == FeatureId.FIND_MY_BUDS && h.writeVals.get(3) == 0,
                "unsolicited WEAR_STATUS in-ear triggers immediate FIND_MY_BUDS=0 kill-switch");

        // Find Buds 4000ms timeout
        tx.onWearStatus(DeviceState.WEAR_NONE);
        h.values.put(FeatureId.FIND_MY_BUDS, 0);
        tx.begin(FeatureId.FIND_MY_BUDS, 1);
        h.clock.tick(FeatureTransactions.FIND_TIMEOUT - 1);
        check(tx.busy(FeatureId.FIND_MY_BUDS), "FIND_MY_BUDS lane active before 4000ms");
        h.clock.tick(1);
        check(!tx.busy(FeatureId.FIND_MY_BUDS) && !tx.isFindBudsRinging(),
                "unflushed FIND_MY_BUDS lane times out at 4000ms and clears ringing");
    }

    public static void main(String[] args) {
        optionAndDescriptorContracts();
        deviceStateFeatureMaps();
        wireDriverBridgeAndHelpers();
        transactionCoalescingAndReadback();
        couplingAbortAndLinkEnded();
        findBudsSafetyContracts();
        System.out.println("PASS " + checks + " feature engine checks");
    }
}

package com.hdz.wearcontrol;

/** Wave 1 pure-JVM regression checks for VolumeGuard.observe and DeviceState null-address handling. */
public final class AuditRegressionChecks {
    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    private static void volumeGuardObserve() {
        VolumeGuard guard = new VolumeGuard();
        guard.unlock();
        check(guard.limit(0, 10, 10) == 7 && !guard.isUnlocked() && guard.needsHighConfirmation(),
                "hitting 70% arms high-volume confirmation");

        guard.observe(7, 10);
        check(guard.needsHighConfirmation() && !guard.isUnlocked(),
                "observing volume at 70% boundary preserves pending high confirmation");
        guard.observe(10, 10);
        check(guard.needsHighConfirmation(),
                "observing volume above 70% preserves pending high confirmation");

        guard.observe(6, 10);
        check(!guard.needsHighConfirmation() && !guard.isUnlocked(),
                "observing external drop below 70% clears stale high confirmation");
        guard.unlock();
        check(guard.limit(6, 10, 10) == 7 && guard.needsHighConfirmation(),
                "re-armed guard after observe(<70%) stops at 70% again");

        guard.unlock();
        check(guard.limit(7, 10, 10) == 10 && guard.isUnlocked(),
                "confirming high volume permits >70%");
        guard.observe(7, 10);
        check(guard.limit(7, 9, 10) == 9,
                "observing volume at 70% preserves existing high-volume authorization");

        guard.observe(6, 10);
        check(guard.limit(6, 10, 10) == 7 && !guard.isUnlocked() && guard.needsHighConfirmation(),
                "observing external volume drop below 70% revokes prior high-volume authorization");

        guard.lock();
        guard.observe(-5, 10);
        check(!guard.isUnlocked() && !guard.needsHighConfirmation(),
                "observe clamps negative volume safely and does not unlock");
        guard.unlock();
        check(guard.limit(0, 10, 10) == 7 && guard.needsHighConfirmation(),
                "confirming up to 70% arms confirmation before high-range clamp check");
        guard.unlock();
        check(guard.limit(7, 10, 10) == 10, "high volume unlocked before high clamp check");
        guard.observe(99, 10);
        check(guard.limit(8, 10, 10) == 10,
                "observe clamps oversized volume to max without resetting highAllowed");

        guard.observe(0, 0);
        guard.observe(3, -1);
        check(guard.limit(8, 10, 10) == 10,
                "observe ignores non-positive max volume cleanly");

        for (int max = 2; max <= 100; max++) {
            int boundary = (int) (max * 7L / 10L);
            if (boundary <= 0) continue;
            VolumeGuard g = new VolumeGuard();
            g.unlock();
            check(g.limit(0, max, max) == boundary && g.needsHighConfirmation(),
                    "boundary stops at 70% floor for max=" + max);
            g.unlock();
            check(g.limit(boundary, max, max) == max,
                    "high volume allowed after confirm for max=" + max);
            g.observe(boundary, max);
            check(g.limit(boundary, max, max) == max,
                    "observe at boundary preserves highAllowed for max=" + max);
            g.observe(boundary - 1, max);
            check(g.limit(boundary - 1, max, max) == boundary && g.needsHighConfirmation(),
                    "observe below boundary revokes highAllowed for max=" + max);
        }
    }

    private static void deviceStateNullAddress() {
        DeviceState state = new DeviceState();
        state.begin(null, "Nameless", Controls.CAP_BATTERY, Controls.CAP_NOISE);
        check("".equals(state.address) && "Nameless".equals(state.name),
                "null address normalizes to empty string");

        state.begin("00:11:22:33:44:55", "Known Buds", Controls.CAP_BATTERY, Controls.CAP_NOISE);
        state.connected = true;
        state.battery(80, 90, 100, 1);
        state.begin(null, null, Controls.CAP_BATTERY_SINGLE, Controls.CAP_BATTERY_SINGLE);
        check("".equals(state.address) && state.name.isEmpty() && !state.connected
                && state.left == -1 && state.right == -1 && state.box == -1 && state.charge == -1,
                "null address after known device resets identity, name, and battery state");

        state.begin(null, "Fallback", Controls.CAP_BATTERY, Controls.CAP_NOISE);
        state.begin("", null, Controls.CAP_BATTERY, Controls.CAP_NOISE);
        check("".equals(state.address) && "Fallback".equals(state.name),
                "null and empty addresses share normalized empty identity");

        state.connected = true;
        state.capabilities(Controls.CAP_BATTERY_SINGLE, Controls.CAP_BATTERY_SINGLE);
        state.battery(55, 66, 77, 3);
        check(state.left == 55 && state.right == -1 && state.box == -1 && state.charge == -1,
                "single-battery mode records only overall battery");
    }

    private static void deviceStateResumeParked() {
        DeviceState state = new DeviceState();
        state.begin("AA:BB:CC:DD:EE:FF", "vivo TWS Air3 Pro",
                Controls.CAP_BATTERY | Controls.CAP_NOISE,
                Controls.CAP_BATTERY | Controls.CAP_NOISE);
        state.connected = true;
        state.battery(95, 90, 80, 0);
        state.mode = Controls.MODE_ANC;
        state.featureSpec(FeatureDescriptor.choice(FeatureId.ANC_LEVEL, Controls.VERIF_REAL, false,
                new OptionItem(0, "轻度", "Mild"),
                new OptionItem(1, "均衡", "Balanced"),
                new OptionItem(2, "深度", "Deep")));
        state.featureValue(FeatureId.ANC_LEVEL, 2);
        state.parked = true;

        // Resuming the same parked earbud preserves cached snapshot seamlessly
        state.resumeParked("AA:BB:CC:DD:EE:FF", "vivo TWS Air3 Pro");
        check(state.connected && state.parked && state.left == 95 && state.right == 90 && state.box == 80
                        && state.mode == Controls.MODE_ANC
                        && Integer.valueOf(2).equals(state.featureValue(FeatureId.ANC_LEVEL))
                        && state.isFeatureWritable(FeatureId.ANC_LEVEL),
                "resumeParked preserves snapshot and writable feature state for same earbud");

        // Full disconnect clears parked & connection state
        state.disconnect();
        check(!state.connected && !state.parked && state.mode == -1
                        && state.featureSpec(FeatureId.ANC_LEVEL) == null,
                "disconnect clears parked flag and cached feature specs");
    }

    public static void main(String[] args) {
        volumeGuardObserve();
        deviceStateNullAddress();
        deviceStateResumeParked();
        System.out.println("PASS " + checks + " audit regression checks");
    }
}

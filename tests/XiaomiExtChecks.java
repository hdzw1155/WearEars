package com.hdz.wearcontrol;

import com.hdz.wearcontrol.xiaomi.XiaomiAuth;
import com.hdz.wearcontrol.xiaomi.XiaomiProtocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static com.hdz.wearcontrol.Controls.*;

/** Unit tests for Xiaomi/Redmi Wave 3 protocol extensions, VID:PID gating, safety gates, and banned opcodes. */
public final class XiaomiExtChecks {
    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    private static byte[] hex(String s) {
        return ProtocolChecks.hex(s);
    }

    private static final class Rig implements WireDriver.Port, Controls.Listener {
        final WireDriver driver;
        final CoreChecks.Clock clock = new CoreChecks.Clock();
        final List<byte[]> frames = new ArrayList<>();
        final List<Runnable> completions = new ArrayList<>();
        final EnumMap<FeatureId, FeatureDescriptor> specs = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, Integer> values = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, String> texts = new EnumMap<>(FeatureId.class);
        int connections, errors, failures, mode = -1, left = -1, right = -1, box = -1, charge = -1;
        Boolean spatial;
        Integer eq;

        Rig(String name) {
            DriverModule module = null;
            for (DriverModule candidate : InstalledDrivers.ALL) {
                if (candidate.matches(name)) {
                    if (module != null) throw new AssertionError("Ambiguous driver for " + name);
                    module = candidate;
                }
            }
            if (module == null) throw new AssertionError("Missing driver for " + name);
            driver = module.create();
            driver.bind(name, this, this);
        }

        void openAndAuth() {
            driver.onOpen();
            byte[] hello = frames.get(0);
            int sn = hello[7] & 255;
            byte[] ans = new byte[17];
            ans[0] = 1;
            System.arraycopy(XiaomiAuth.e21(Arrays.copyOfRange(hello, 9, 25)), 0, ans, 1, 16);
            driver.received(XiaomiProtocol.response(0x50, 1, sn, 0, ans));
            byte[] done = frames.get(1);
            driver.received(XiaomiProtocol.response(0x51, 1, done[7] & 255, 0, new byte[]{1}));
            check(connections == 1 && driver.isConnected(), "authenticated session ready");
        }

        void receive(byte[] bytes) {
            driver.received(bytes);
        }

        byte[] lastFrame() {
            return frames.get(frames.size() - 1);
        }

        void flushLast() {
            int i = completions.size() - 1;
            Runnable r = completions.set(i, null);
            if (r != null) r.run();
        }

        void assertNoBannedOpcodes() {
            for (byte[] f : frames) {
                int op = f[4] & 0xFF;
                check(!XiaomiProtocol.isBannedOpcode(op), "banned opcode emitted: 0x" + Integer.toHexString(op));
                check(op != 0x06 && op != 0x03 && op != 0x0B && !(op >= 0xE1 && op <= 0xE7)
                        && !(op >= 0xC1 && op <= 0xC4) && !(op >= 0xD0 && op <= 0xD3),
                        "explicitly forbidden opcode emitted: 0x" + Integer.toHexString(op));
            }
        }

        @Override public void send(byte[] bytes, Runnable flushed) {
            frames.add(bytes.clone());
            completions.add(flushed);
        }
        @Override public void after(Runnable work, long delay) { clock.after(taskOrNoop(work), delay); }
        private static Runnable taskOrNoop(Runnable work) { return work == null ? () -> { } : work; }
        @Override public void cancel(Runnable work) { clock.cancel(work); }
        @Override public void useBle(UUID s, UUID n, UUID w, long d, long t) { }
        @Override public void fail(String message) { failures++; }
        @Override public long millis() { return clock.now; }
        @Override public void trace(String tag, String message) { }
        @Override public void onConnected(String name) { connections++; }
        @Override public void onDisconnected(String reason) { errors++; }
        @Override public void onError(String msg) { errors++; }
        @Override public void onBattery(int l, int r, int b, int c) { left = l; right = r; box = b; charge = c; }
        @Override public void onNoiseMode(int value) { mode = value; }
        @Override public void onSpatial(Boolean value) { spatial = value; }
        @Override public void onDeepX(Integer value) { eq = value; }
        @Override public void onEqualizer(Integer value) { eq = value; }
        @Override public void onFeatureSpec(FeatureDescriptor spec) { if (spec != null) specs.put(spec.id, spec); }
        @Override public void onFeatureValue(FeatureId id, int value) { if (id != null) values.put(id, value); }
        @Override public void onFeatureText(FeatureId id, String text) { if (id != null) texts.put(id, text); }
    }

    private static byte[] frameParams(byte[] frame) {
        // FE DC BA | flags | op | len(2B) | sn(1B) | params... | EF
        return Arrays.copyOfRange(frame, 8, frame.length - 1);
    }

    private static void bannedSafetyGuards() {
        int[] bannedOps = {0x06, 0x03, 0x0B, 0xC1, 0xC2, 0xC3, 0xC4, 0xD0, 0xD1, 0xD2, 0xD3,
                0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7};
        for (int op : bannedOps) {
            boolean rejectedCmd = false, rejectedResp = false;
            try { XiaomiProtocol.command(op, 4, 1, new byte[0]); } catch (IllegalArgumentException e) { rejectedCmd = true; }
            try { XiaomiProtocol.response(op, 4, 1, 0, new byte[0]); } catch (IllegalArgumentException e) { rejectedResp = true; }
            check(rejectedCmd && rejectedResp, "encoder rejects banned opcode 0x" + Integer.toHexString(op));
        }
        int[] bannedCfgs = {52, 75, 77, 86, 119, 127, 0xEEEE};
        for (int cfg : bannedCfgs) {
            boolean rejected = false;
            try { XiaomiProtocol.configTlv(cfg, new byte[]{0}); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "configTlv rejects banned config ID " + cfg);
        }
    }

    private static void vidPidAndFirmwareGating() {
        check("buds6".equals(XiaomiProtocol.capsForVidPid(0x2717, 0x509F).id)
                && "buds6".equals(XiaomiProtocol.capsForVidPid(0x2717, 0x50A0).id), "Buds 6 VID:PID");
        check("buds6pro".equals(XiaomiProtocol.capsForVidPid(0x2717, 0x509D).id)
                && "buds6pro".equals(XiaomiProtocol.capsForVidPid(0x2717, 0x509E).id)
                && "buds6pro".equals(XiaomiProtocol.capsForVidPid(0x2717, 0x50AF).id), "Buds 6 Pro VID:PID");
        check("buds5".equals(XiaomiProtocol.capsForVidPid(0x2717, 0x506A).id)
                && "buds5".equals(XiaomiProtocol.capsForVidPid(0x2717, 0x506B).id)
                && "buds5".equals(XiaomiProtocol.capsForVidPid(0x2717, 0x5075).id), "Buds 5 VID:PID");
        check("buds4pro".equals(XiaomiProtocol.capsForVidPid(0x5A4D, 0xEA0E).id)
                && "buds4pro".equals(XiaomiProtocol.capsForVidPid(0x5A4D, 0xEA0F).id), "Buds 4 Pro VID:PID");
        check("buds3pro".equals(XiaomiProtocol.capsForVidPid(0x5A4D, 0xEA0A).id)
                && "buds3pro".equals(XiaomiProtocol.capsForVidPid(0x5A4D, 0xEA0B).id), "Buds 3 Pro VID:PID");
        check("1.2.3.4".equals(XiaomiProtocol.firmware(hex("03 01 12 34"))), "2B 4-nibble firmware");
        check("2.1.3.5".equals(XiaomiProtocol.firmware(hex("05 01 02 01 03 05"))), "4B firmware");

        // Unknown/renamed device starts with no optional specs; VID:PID in 0x02 unlocks Buds 6 Pro features.
        Rig renamed = new Rig("Redmi Custom");
        renamed.openAndAuth();
        check(!renamed.specs.containsKey(FeatureId.ANC_LEVEL)
                && !renamed.specs.containsKey(FeatureId.LOW_LATENCY)
                && !renamed.specs.containsKey(FeatureId.SPATIAL_AUDIO)
                && !renamed.specs.containsKey(FeatureId.WIND_REDUCTION),
                "unknown Xiaomi name refuses speculative optional features");
        int before = renamed.frames.size();
        renamed.driver.writeFeature(FeatureId.LOW_LATENCY, 1, () -> { });
        check(renamed.frames.size() == before, "unknown model refuses write to unconfirmed feature");

        // 0x02 response with FW TLV 0x01 + VID:PID TLV 0x03 (2717:509D = Buds 6 Pro) + Battery TLV 0x07.
        renamed.receive(XiaomiProtocol.response(0x02, 4, 0, 0,
                hex("05 01 02 01 03 05 05 03 27 17 50 9D 04 07 55 5A 64")));
        check("2.1.3.5".equals(renamed.texts.get(FeatureId.FIRMWARE_INFO)), "FIRMWARE_INFO decoded from TLV 0x01");
        check(renamed.left == 85 && renamed.right == 90 && renamed.box == 100, "battery decoded alongside FW/VID:PID");
        check(renamed.specs.containsKey(FeatureId.ANC_LEVEL)
                && renamed.specs.containsKey(FeatureId.TRANS_MODE)
                && renamed.specs.containsKey(FeatureId.EQUALIZER)
                && renamed.specs.containsKey(FeatureId.LOW_LATENCY)
                && renamed.specs.containsKey(FeatureId.MULTIPOINT)
                && renamed.specs.containsKey(FeatureId.WEAR_STATUS)
                && renamed.specs.containsKey(FeatureId.WEAR_DETECTION)
                && renamed.specs.containsKey(FeatureId.FIND_MY_BUDS)
                && renamed.specs.containsKey(FeatureId.SPATIAL_AUDIO)
                && renamed.specs.containsKey(FeatureId.HEAD_TRACKING)
                && renamed.specs.containsKey(FeatureId.SPATIAL_SCENE)
                && !renamed.specs.containsKey(FeatureId.WIND_REDUCTION),
                "Buds 6 Pro VID:PID unlocks exact capability set without Wind Noise");
        renamed.assertNoBannedOpcodes();
    }

    private static void buds6FeaturesAndSafety() {
        Rig r = new Rig("Redmi Buds 6");
        r.openAndAuth();

        // Confirm top-level noise (0x000B) and verify profile 0 uses 0x08 for top mode, 0xF2 0x000B for sub-level.
        r.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("04 00 0B 01 02")));
        check((r.driver.caps() & CAP_NOISE) != 0 && r.mode == MODE_ANC
                && Integer.valueOf(2).equals(r.values.get(FeatureId.ANC_LEVEL)),
                "0x000B ANC Deep decoded");

        int[] flushed = {0};
        r.driver.changeNoise(MODE_TRANS, () -> flushed[0]++);
        check((r.lastFrame()[4] & 0xFF) == 0x08
                && Arrays.equals(frameParams(r.lastFrame()), hex("02 04 02")),
                "profile 0 top-level mode switch still uses 0x08 SET_TARGET_INFO");
        r.flushLast();
        check(flushed[0] == 1, "changeNoise flush callback fires");

        r.driver.writeFeature(FeatureId.ANC_LEVEL, 1, () -> flushed[0]++);
        check((r.lastFrame()[4] & 0xFF) == 0xF2
                && Arrays.equals(frameParams(r.lastFrame()), hex("04 00 0B 01 01")),
                "ANC_LEVEL sub-level writes 0xF2 Config 0x000B");
        r.flushLast();
        check(flushed[0] == 2, "ANC_LEVEL flush callback fires");

        // Transparency sub-modes (0x000B mode=02).
        r.driver.writeFeature(FeatureId.TRANS_MODE, 1, () -> { });
        check((r.lastFrame()[4] & 0xFF) == 0xF2
                && Arrays.equals(frameParams(r.lastFrame()), hex("04 00 0B 02 01")),
                "TRANS_MODE writes 0xF2 Config 0x000B mode 02");
        r.receive(XiaomiProtocol.command(0xF4, 4, 7, hex("04 00 0B 02 02")));
        check(r.mode == MODE_TRANS && Integer.valueOf(2).equals(r.values.get(FeatureId.TRANS_MODE)),
                "0xF4 0x000B updates TRANS_MODE");

        // Low-latency 0x002F and Multipoint 0x0004.
        r.driver.writeFeature(FeatureId.LOW_LATENCY, 1, () -> { });
        check((r.lastFrame()[4] & 0xFF) == 0xF2
                && Arrays.equals(frameParams(r.lastFrame()), hex("03 00 2F 01")),
                "LOW_LATENCY writes 0x002F");
        r.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("03 00 2F 01 03 00 04 01")));
        check(Integer.valueOf(1).equals(r.values.get(FeatureId.LOW_LATENCY))
                && Integer.valueOf(1).equals(r.values.get(FeatureId.MULTIPOINT)),
                "LOW_LATENCY and MULTIPOINT decoded");
        r.driver.writeFeature(FeatureId.MULTIPOINT, 0, () -> { });
        check((r.lastFrame()[4] & 0xFF) == 0xF2
                && Arrays.equals(frameParams(r.lastFrame()), hex("03 00 04 00")),
                "MULTIPOINT off writes 0x0004 00");

        // Equalizer 0x0007.
        r.driver.writeFeature(FeatureId.EQUALIZER, 5, () -> { });
        check((r.lastFrame()[4] & 0xFF) == 0xF2
                && Arrays.equals(frameParams(r.lastFrame()), hex("03 00 07 05")),
                "EQUALIZER writes 0x0007");
        r.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("03 00 07 05")));
        check(Integer.valueOf(5).equals(r.values.get(FeatureId.EQUALIZER))
                && Integer.valueOf(5).equals(r.eq) && (r.driver.caps() & CAP_EQUALIZER) != 0,
                "EQUALIZER 0x0007 readback confirms CAP_EQUALIZER");

        // Inverted Wear Detection 0x08 [02 06 00/01] and 0x09 attr 0x0A readback.
        r.driver.queryFeature(FeatureId.WEAR_DETECTION);
        check((r.lastFrame()[4] & 0xFF) == 0x09 && Arrays.equals(frameParams(r.lastFrame()), hex("0A")),
                "WEAR_DETECTION queries 0x09 attr 0x0A");
        r.receive(XiaomiProtocol.response(0x09, 4, 0, 0, hex("02 0A 01")));
        check(Integer.valueOf(0).equals(r.values.get(FeatureId.WEAR_DETECTION)),
                "0x09 attr 0x0A=01 decodes inverted as OFF (0)");
        r.driver.writeFeature(FeatureId.WEAR_DETECTION, 1, () -> { });
        check((r.lastFrame()[4] & 0xFF) == 0x08 && Arrays.equals(frameParams(r.lastFrame()), hex("02 06 00")),
                "WEAR_DETECTION ON writes inverted 0x08 [02 06 00]");
        r.driver.writeFeature(FeatureId.WEAR_DETECTION, 0, () -> { });
        check((r.lastFrame()[4] & 0xFF) == 0x08 && Arrays.equals(frameParams(r.lastFrame()), hex("02 06 01")),
                "WEAR_DETECTION OFF writes inverted 0x08 [02 06 01]");

        // Live Wear Status 0x000C (bit3=L, bit2=R).
        r.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("03 00 0C 03"))); // in-case bits only -> NONE
        check(Integer.valueOf(DeviceState.WEAR_NONE).equals(r.values.get(FeatureId.WEAR_STATUS)), "0x000C in-case = WEAR_NONE");
        r.receive(XiaomiProtocol.command(0xF4, 4, 1, hex("03 00 0C 08")));
        check(Integer.valueOf(DeviceState.WEAR_LEFT).equals(r.values.get(FeatureId.WEAR_STATUS)), "0x000C bit3 = WEAR_LEFT");
        r.receive(XiaomiProtocol.command(0xF4, 4, 2, hex("03 00 0C 04")));
        check(Integer.valueOf(DeviceState.WEAR_RIGHT).equals(r.values.get(FeatureId.WEAR_STATUS)), "0x000C bit2 = WEAR_RIGHT");
        r.receive(XiaomiProtocol.command(0xF4, 4, 3, hex("03 00 0C 0C")));
        check(Integer.valueOf(DeviceState.WEAR_BOTH).equals(r.values.get(FeatureId.WEAR_STATUS)), "0x000C bit3|bit2 = WEAR_BOTH");

        // Find My Earbuds gated by WEAR_STATUS + 0x0009 stop-before-start + live wear kill-switch.
        FeatureDescriptor findSpec = r.specs.get(FeatureId.FIND_MY_BUDS);
        check(findSpec != null && findSpec.verif == VERIF_UNTESTED && findSpec.safety == FeatureDescriptor.SAFETY_FIND_BUDS,
                "FIND_MY_BUDS descriptor metadata");
        int beforeInEar = r.frames.size();
        r.driver.writeFeature(FeatureId.FIND_MY_BUDS, 1, () -> { });
        check(r.frames.size() == beforeInEar && r.errors > 0,
                "FIND_MY_BUDS blocked when WEAR_STATUS is in-ear");

        r.receive(XiaomiProtocol.command(0xF4, 4, 4, hex("03 00 0C 00")));
        check(Integer.valueOf(DeviceState.WEAR_NONE).equals(r.values.get(FeatureId.WEAR_STATUS)), "WEAR_NONE cleared");
        int beforeRing = r.frames.size();
        r.driver.writeFeature(FeatureId.FIND_MY_BUDS, 3, () -> { });
        check(r.frames.size() == beforeRing + 2
                && (r.frames.get(beforeRing)[4] & 0xFF) == 0xF2
                && Arrays.equals(frameParams(r.frames.get(beforeRing)), hex("04 00 09 00 03"))
                && (r.frames.get(beforeRing + 1)[4] & 0xFF) == 0xF2
                && Arrays.equals(frameParams(r.frames.get(beforeRing + 1)), hex("04 00 09 01 03")),
                "FIND_MY_BUDS sends stop [00 03] before ring [01 03]");
        int ringSn = r.lastFrame()[7] & 0xFF;
        r.receive(XiaomiProtocol.response(0xF2, 4, ringSn, 0, new byte[0]));
        check(Integer.valueOf(3).equals(r.values.get(FeatureId.FIND_MY_BUDS)), "FIND_MY_BUDS confirmed by 0xF2 ACK");

        // Live transition to in-ear auto-stops ringing! (command(0xF4) has 0x40 ack flag, so 0xF2 stop + 0xF4 ack)
        int beforeKill = r.frames.size();
        r.receive(XiaomiProtocol.command(0xF4, 4, 5, hex("03 00 0C 04")));
        check(r.frames.size() == beforeKill + 2
                && (r.frames.get(beforeKill)[4] & 0xFF) == 0xF2
                && Arrays.equals(frameParams(r.frames.get(beforeKill)), hex("04 00 09 00 03"))
                && (r.frames.get(beforeKill + 1)[4] & 0xFF) == 0xF4
                && Integer.valueOf(0).equals(r.values.get(FeatureId.FIND_MY_BUDS)),
                "live in-ear transition auto-stops active Find My Buds");

        // Buds 6 Fixed Spatial Audio 0x004F (devOnly=true, SAFETY_AUDIO_WARN).
        FeatureDescriptor spSpec = r.specs.get(FeatureId.SPATIAL_AUDIO);
        check(spSpec != null && spSpec.devOnly && spSpec.verif == VERIF_UNTESTED
                && spSpec.safety == FeatureDescriptor.SAFETY_AUDIO_WARN,
                "Buds 6 SPATIAL_AUDIO descriptor metadata");
        r.driver.writeFeature(FeatureId.SPATIAL_AUDIO, 1, () -> { });
        check(Arrays.equals(frameParams(r.lastFrame()), hex("03 00 4F 01")), "Buds 6 spatial writes 0x004F");
        r.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("03 00 4F 01")));
        check(Boolean.TRUE.equals(r.spatial) && (r.driver.caps() & CAP_SPATIAL) != 0, "0x004F confirms CAP_SPATIAL");

        r.assertNoBannedOpcodes();
    }

    private static void buds6ProAndLegacyModels() {
        // Buds 6 Pro: Spatial 0x001D/0x001E + Scenes 0x0036 + Adaptive 0x0025.
        Rig p6 = new Rig("Redmi Buds 6 Pro");
        p6.openAndAuth();
        FeatureDescriptor sp = p6.specs.get(FeatureId.SPATIAL_AUDIO);
        FeatureDescriptor ht = p6.specs.get(FeatureId.HEAD_TRACKING);
        FeatureDescriptor sc = p6.specs.get(FeatureId.SPATIAL_SCENE);
        check(sp != null && ht != null && sc != null && sp.devOnly && ht.devOnly && sc.devOnly
                && sp.safety == FeatureDescriptor.SAFETY_AUDIO_WARN
                && ht.safety == FeatureDescriptor.SAFETY_AUDIO_WARN
                && sc.safety == FeatureDescriptor.SAFETY_AUDIO_WARN,
                "Buds 6 Pro spatial/HT/scene devOnly + SAFETY_AUDIO_WARN");

        p6.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("03 00 1E 02")));
        check(Integer.valueOf(0).equals(p6.values.get(FeatureId.SPATIAL_AUDIO))
                && Integer.valueOf(0).equals(p6.values.get(FeatureId.HEAD_TRACKING)), "0x001E 0x02 = Off");
        p6.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("03 00 1E 03")));
        check(Integer.valueOf(1).equals(p6.values.get(FeatureId.SPATIAL_AUDIO))
                && Integer.valueOf(0).equals(p6.values.get(FeatureId.HEAD_TRACKING)), "0x001E 0x03 = Fixed");
        p6.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("03 00 1E 0B")));
        check(Integer.valueOf(1).equals(p6.values.get(FeatureId.SPATIAL_AUDIO))
                && Integer.valueOf(1).equals(p6.values.get(FeatureId.HEAD_TRACKING)), "0x001E 0x0B = Head Tracking");

        p6.driver.writeFeature(FeatureId.HEAD_TRACKING, 0, () -> { });
        check(Arrays.equals(frameParams(p6.lastFrame()), hex("03 00 1D 03")), "HT off writes 0x001D 0x03");
        p6.driver.writeFeature(FeatureId.HEAD_TRACKING, 1, () -> { });
        check(Arrays.equals(frameParams(p6.lastFrame()), hex("03 00 1D 0B")), "HT on writes 0x001D 0x0B");
        p6.driver.writeFeature(FeatureId.SPATIAL_AUDIO, 0, () -> { });
        check(Arrays.equals(frameParams(p6.lastFrame()), hex("03 00 1D 02")), "Spatial off writes 0x001D 0x02");

        p6.driver.writeFeature(FeatureId.SPATIAL_SCENE, 4, () -> { });
        check(Arrays.equals(frameParams(p6.lastFrame()), hex("04 00 36 01 04")), "Spatial scene writes 0x0036 [01 04]");
        p6.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("04 00 36 01 04")));
        check(Integer.valueOf(4).equals(p6.values.get(FeatureId.SPATIAL_SCENE)), "0x0036 decoded");

        p6.driver.writeFeature(FeatureId.ANC_LEVEL, 3, () -> { });
        check(Arrays.equals(frameParams(p6.lastFrame()), hex("03 00 25 01")), "Buds 6 Pro Adaptive ANC writes 0x0025 01");
        p6.receive(XiaomiProtocol.response(0xF3, 4, 0, 0, hex("03 00 25 01")));
        check(Integer.valueOf(3).equals(p6.values.get(FeatureId.ANC_LEVEL)), "0x0025 01 reports ANC_LEVEL=3");
        int beforeSwitch = p6.frames.size();
        p6.driver.writeFeature(FeatureId.ANC_LEVEL, 2, () -> { });
        check(p6.frames.size() == beforeSwitch + 2
                && Arrays.equals(frameParams(p6.frames.get(beforeSwitch)), hex("03 00 25 00"))
                && Arrays.equals(frameParams(p6.frames.get(beforeSwitch + 1)), hex("04 00 0B 01 02")),
                "switching away from Adaptive clears 0x0025 then writes 0x000B Deep");
        p6.assertNoBannedOpcodes();

        // Buds 3 Pro: Adaptive level 3 on 0x000B, no EQ, no Trans sub-mode.
        Rig p3 = new Rig("Redmi Buds 3 Pro");
        p3.openAndAuth();
        check(!p3.specs.containsKey(FeatureId.EQUALIZER) && !p3.specs.containsKey(FeatureId.TRANS_MODE)
                && p3.specs.get(FeatureId.ANC_LEVEL).findOption(3) != null,
                "Buds 3 Pro supports Adaptive 0x000B=3 without EQ or Trans sub-modes");
        p3.driver.writeFeature(FeatureId.ANC_LEVEL, 3, () -> { });
        check(Arrays.equals(frameParams(p3.lastFrame()), hex("04 00 0B 01 03")),
                "Buds 3 Pro Adaptive writes 0x000B [01 03]");
        p3.assertNoBannedOpcodes();

        // Buds 4 Pro: EQ 0x15 Balanced preset.
        Rig p4 = new Rig("Redmi Buds 4 Pro");
        p4.openAndAuth();
        FeatureDescriptor eq4 = p4.specs.get(FeatureId.EQUALIZER);
        check(eq4 != null && eq4.isValidValue(0x15) && !eq4.isValidValue(0x00),
                "Buds 4 Pro uses 0x15 Balanced EQ preset");
        p4.assertNoBannedOpcodes();
    }

    public static void main(String[] args) {
        bannedSafetyGuards();
        vidPidAndFirmwareGating();
        buds6FeaturesAndSafety();
        buds6ProAndLegacyModels();
        System.out.println("PASS " + checks + " Xiaomi extension checks");
    }
}

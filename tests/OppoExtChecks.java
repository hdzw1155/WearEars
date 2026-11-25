package com.hdz.wearcontrol;

import com.hdz.wearcontrol.oppo.OppoOpoClient;
import com.hdz.wearcontrol.oppo.OppoProtocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static com.hdz.wearcontrol.Controls.*;

/** Unit tests for OPPO / OnePlus / realme Wave 3 extended OPOv1 protocol and driver features. */
public final class OppoExtChecks {
    private static int checks;

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }

    private static byte[] hex(String text) {
        return ProtocolChecks.hex(text);
    }

    private static byte[] capPayload(int... groups) {
        int maxGrp = 0;
        for (int g : groups) if (g > maxGrp) maxGrp = g;
        int len = Math.max(9, (maxGrp >>> 3) + 1);
        byte[] p = new byte[1 + len];
        p[0] = 0x00;
        for (int g : groups) p[1 + (g >>> 3)] |= (byte) (1 << (g & 7));
        return p;
    }

    private static byte[] pidPayloadLe(int pid) {
        return new byte[]{0x00, (byte) (pid & 0xFF), (byte) ((pid >>> 8) & 0xFF), (byte) ((pid >>> 16) & 0xFF)};
    }

    private static final class Rig implements WireDriver.Port, Controls.Listener {
        final WireDriver driver = new OppoOpoClient();
        final CoreChecks.Clock clock = new CoreChecks.Clock();
        final List<byte[]> frames = new ArrayList<>();
        final List<Runnable> completions = new ArrayList<>();
        final EnumMap<FeatureId, FeatureDescriptor> specs = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, Integer> values = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, String> texts = new EnumMap<>(FeatureId.class);
        int connections, mode = -1;
        Integer eq;
        Boolean spatial;

        Rig(String name) {
            driver.bind(name, this, this);
        }

        void open() { driver.onOpen(); }

        void receiveCmd(int cmd, int seq, byte[] payload) {
            driver.received(OppoProtocol.encode(cmd, seq, payload));
        }

        void flushLast() {
            int i = completions.size() - 1;
            Runnable r = completions.set(i, null);
            if (r != null) r.run();
        }

        OppoProtocol.Frame lastDecoded() {
            byte[] raw = frames.get(frames.size() - 1);
            List<OppoProtocol.Frame> decoded = new OppoProtocol.Decoder().feed(raw, raw.length);
            check(decoded.size() == 1, "outbound frame decodes cleanly");
            return decoded.get(0);
        }

        void runHandshake(byte[] helloCaps, byte[] pid, byte[] features010D, byte[] anc, byte[] batt,
                          byte[] wear, byte[] fw) {
            open();
            for (int i = 0; i < 10; i++) {
                clock.tick(i == 0 ? 300 : 200);
                if (i == 0 && helloCaps != null) receiveCmd(OppoProtocol.RSP_HELLO, i + 1, helloCaps);
                if (i == 1 && pid != null) receiveCmd(OppoProtocol.RSP_PRODUCT, i + 1, pid);
                if (i == 4 && features010D != null) receiveCmd(OppoProtocol.RSP_FEATURE, i + 1, features010D);
                if (i == 5 && anc != null) receiveCmd(OppoProtocol.RSP_ANC, i + 1, anc);
                if (i == 6 && batt != null) receiveCmd(OppoProtocol.RSP_BATTERY, i + 1, batt);
                if (i == 7 && wear != null) receiveCmd(OppoProtocol.RSP_WEAR, i + 1, wear);
                if (i == 9 && fw != null) receiveCmd(OppoProtocol.RSP_FIRMWARE, i + 1, fw);
                flushLast();
            }
            clock.tick(400);
            check(connections == 1, "handshake completes and connects");
        }

        @Override public void send(byte[] bytes, Runnable flushed) {
            frames.add(bytes.clone());
            completions.add(flushed);
        }
        @Override public void after(Runnable work, long delay) { clock.after(work, delay); }
        @Override public void cancel(Runnable work) { clock.cancel(work); }
        @Override public void useBle(UUID s, UUID n, UUID w, long d, long t) { }
        @Override public void fail(String message) { }
        @Override public long millis() { return clock.now; }
        @Override public void trace(String tag, String message) { }
        @Override public void onConnected(String deviceName) { connections++; }
        @Override public void onDisconnected(String reason) { }
        @Override public void onError(String msg) { }
        @Override public void onBattery(int l, int r, int b, int c) { }
        @Override public void onNoiseMode(int value) { mode = value; }
        @Override public void onSpatial(Boolean state) { spatial = state; }
        @Override public void onDeepX(Integer value) { eq = value; }
        @Override public void onEqualizer(Integer value) { eq = value; }
        @Override public void onFeatureSpec(FeatureDescriptor spec) { specs.put(spec.id, spec); }
        @Override public void onFeatureValue(FeatureId id, int value) { values.put(id, value); }
        @Override public void onFeatureText(FeatureId id, String text) { texts.put(id, text); }
    }

    private static void discoveryAndDecoders() {
        byte[] bitmap = OppoProtocol.capBitmap(capPayload(5, 7, 8, 10, 43, 47));
        check(bitmap != null
                && OppoProtocol.hasCapGroup(bitmap, 5)
                && OppoProtocol.hasCapGroup(bitmap, 7)
                && OppoProtocol.hasCapGroup(bitmap, 8)
                && OppoProtocol.hasCapGroup(bitmap, 10)
                && OppoProtocol.hasCapGroup(bitmap, 43)
                && OppoProtocol.hasCapGroup(bitmap, 47)
                && !OppoProtocol.hasCapGroup(bitmap, 6)
                && !OppoProtocol.hasCapGroup(bitmap, 68),
                "0x8100 69-group capability bitmap decodes target bits");
        check(OppoProtocol.capBitmap(hex("01 FF FF")) == null, "non-zero 0x8100 status rejected");

        int[] pids = {
                OppoProtocol.PID_ENCO_AIR5_PRO,
                OppoProtocol.PID_ENCO_FREE4,
                OppoProtocol.PID_ENCO_X2,
                OppoProtocol.PID_ENCO_X3,
                OppoProtocol.PID_OP_BUDS_PRO_2,
                OppoProtocol.PID_OP_BUDS_PRO_3,
                OppoProtocol.PID_OP_BUDS_4,
                OppoProtocol.PID_REALME_AIR7_PRO
        };
        for (int pid : pids) {
            check(OppoProtocol.productId(pidPayloadLe(pid)) == pid,
                    "0x8103 LE PID decodes 0x" + Integer.toHexString(pid));
            byte[] raw = new byte[]{0x00, (byte) ((pid >>> 16) & 0xFF), (byte) ((pid >>> 8) & 0xFF), (byte) (pid & 0xFF)};
            check(OppoProtocol.productId(raw) == pid,
                    "0x8103 wire-order PID decodes 0x" + Integer.toHexString(pid));
        }
        check(OppoProtocol.productId(hex("01 10 C4 06")) == -1 && OppoProtocol.productId(hex("00 10 C4")) == -1,
                "invalid 0x8103 PID payloads rejected");

        check("1.2.3.4".equals(OppoProtocol.firmware(hex("00 31 2E 32 2E 33 2E 34 00 00"))),
                "0x8105 firmware string trims NUL padding");
        check(OppoProtocol.firmware(hex("01 31 2E 30")).isEmpty(), "non-zero 0x8105 status rejected");

        int[][] switches = OppoProtocol.featureSwitches(hex("00 05 04 01 06 00 11 01 1A 01 1B 00"));
        check(switches != null && switches.length == 5
                && switches[0][0] == 0x04 && switches[0][1] == 1
                && switches[1][0] == 0x06 && switches[1][1] == 0
                && switches[2][0] == 0x11 && switches[2][1] == 1
                && switches[3][0] == 0x1A && switches[3][1] == 1
                && switches[4][0] == 0x1B && switches[4][1] == 0,
                "0x810D batch feature switch table decodes all pairs");
        check(OppoProtocol.featureSwitches(hex("00 02 04 01")) == null, "truncated 0x810D rejected");

        check(OppoProtocol.wearStatus(hex("00 03 01 03 02 01 03 04"), false) == DeviceState.WEAR_LEFT,
                "0x8109 bit1(0x02) maps left in-ear");
        check(OppoProtocol.wearStatus(hex("02 02 01 02 02 02"), true) == DeviceState.WEAR_BOTH,
                "0x0204 ev 0x02 maps both buds in-ear");
        check(OppoProtocol.wearStatus(hex("02 02 01 01 02 00"), true) == DeviceState.WEAR_NONE,
                "out-of-case without bit1 is not in-ear");
        check(OppoProtocol.wearStatus(hex("02 01 03 04"), true) == -1,
                "case-only wear packet does not overwrite bud status");
    }

    private static void air5ProAndFree4AncAndSwitches() {
        // Air5 Pro: grp 5,7,8,10; 0x810D has 0x04, 0x06, 0x11 (NO 0x1A wind!)
        Rig air5 = new Rig("OPPO Enco Air5 Pro");
        air5.runHandshake(
                capPayload(5, 7, 8, 10),
                pidPayloadLe(OppoProtocol.PID_ENCO_AIR5_PRO),
                hex("00 03 04 01 06 00 11 01"),
                hex("00 01 01 40 00"),
                hex("00 02 01 5A 02 55"),
                hex("00 02 01 01 02 01"),
                hex("00 31 30 35 2E 31 2E 30")
        );
        check(air5.mode == MODE_ANC && Integer.valueOf(0x40).equals(air5.values.get(FeatureId.ANC_LEVEL)),
                "Air5 Pro decodes Mild ANC 0x40");
        check(air5.specs.get(FeatureId.ANC_LEVEL).options.length == 4
                && air5.specs.get(FeatureId.ANC_LEVEL).findOption(0x0800) == null,
                "Air5 Pro has 4 ANC sub-levels without 0x0800");
        check(air5.specs.get(FeatureId.TRANS_MODE) == null, "Air5 Pro hides TRANS_MODE");
        check(air5.specs.get(FeatureId.WIND_REDUCTION) == null,
                "Wind 0x1A stays hidden when absent from 0x810D");
        check("105.1.0".equals(air5.texts.get(FeatureId.FIRMWARE_INFO)), "Air5 Pro firmware reported");
        check(Integer.valueOf(1).equals(air5.values.get(FeatureId.WEAR_DETECTION))
                && Integer.valueOf(0).equals(air5.values.get(FeatureId.LOW_LATENCY))
                && Integer.valueOf(1).equals(air5.values.get(FeatureId.MULTIPOINT))
                && Integer.valueOf(DeviceState.WEAR_NONE).equals(air5.values.get(FeatureId.WEAR_STATUS)),
                "0x810D switches and 0x8109 wear populated");

        // Write ANC_LEVEL = Moderate (0x20), verify 0x0404 + 350ms 0x010C re-query + 0x8404 status 0x0E re-query
        int before = air5.frames.size();
        boolean[] flushed = {false};
        air5.driver.writeFeature(FeatureId.ANC_LEVEL, 0x20, () -> flushed[0] = true);
        OppoProtocol.Frame f = air5.lastDecoded();
        check(f.command == OppoProtocol.CMD_ANC_SET && f.sequence != 0xF3 && f.sequence != 0xFF
                && Arrays.equals(f.payload, hex("01 01 20")),
                "ANC_LEVEL writes 0x0404 [01 01 20] with nextSeq");
        air5.flushLast();
        check(flushed[0], "ANC_LEVEL flush runs callback");
        air5.clock.tick(349);
        check(air5.frames.size() == before + 1, "re-query waits full 350ms");
        air5.clock.tick(1);
        OppoProtocol.Frame q = air5.lastDecoded();
        check(q.command == OppoProtocol.CMD_ANC_QUERY && Arrays.equals(q.payload, hex("01 01")),
                "0x0404 write schedules 0x010C re-query");

        // 0x8404 ambiguous 0x0E triggers 0x010C re-query
        int before0E = air5.frames.size();
        air5.receiveCmd(OppoProtocol.RSP_ANC_ACK, 0x10, hex("0E"));
        check(air5.frames.size() == before0E + 1
                && air5.lastDecoded().command == OppoProtocol.CMD_ANC_QUERY,
                "0x8404 status 0x0E triggers 0x010C re-query");

        // Write Low-Latency, Multipoint, Wear Detection + push updates
        air5.driver.writeFeature(FeatureId.LOW_LATENCY, 1, null);
        check(air5.lastDecoded().command == OppoProtocol.CMD_FEATURE_SET
                && Arrays.equals(air5.lastDecoded().payload, hex("06 01")), "LOW_LATENCY writes 0x0403 [06 01]");
        air5.receiveCmd(OppoProtocol.CMD_EVENT, 0xFF, hex("05 01"));
        check(Integer.valueOf(1).equals(air5.values.get(FeatureId.LOW_LATENCY)), "0x0204 ev 0x05 updates LOW_LATENCY");
        air5.receiveCmd(OppoProtocol.CMD_FEATURE_NOTIFY, 0xFF, hex("06 00"));
        check(Integer.valueOf(0).equals(air5.values.get(FeatureId.LOW_LATENCY)), "0x0503 updates LOW_LATENCY");

        air5.driver.writeFeature(FeatureId.MULTIPOINT, 0, null);
        check(Arrays.equals(air5.lastDecoded().payload, hex("11 00")), "MULTIPOINT writes 0x0403 [11 00]");
        air5.driver.writeFeature(FeatureId.WEAR_DETECTION, 0, null);
        check(Arrays.equals(air5.lastDecoded().payload, hex("04 00")), "WEAR_DETECTION writes 0x0403 [04 00]");

        // Live wear event 0x0204 ev 0x02 (single-bud partial & full)
        air5.receiveCmd(OppoProtocol.CMD_EVENT, 0xFF, hex("02 01 02 03"));
        check(Integer.valueOf(DeviceState.WEAR_RIGHT).equals(air5.values.get(FeatureId.WEAR_STATUS)),
                "0x0204 ev 0x02 updates WEAR_STATUS to RIGHT");
        air5.receiveCmd(OppoProtocol.CMD_EVENT, 0xFF, hex("02 01 01 02"));
        check(Integer.valueOf(DeviceState.WEAR_BOTH).equals(air5.values.get(FeatureId.WEAR_STATUS)),
                "partial left in-ear push merges to WEAR_BOTH");

        // Attempted Wind write while hidden must emit nothing
        int beforeWind = air5.frames.size();
        air5.driver.writeFeature(FeatureId.WIND_REDUCTION, 1, null);
        check(air5.frames.size() == beforeWind, "hidden WIND_REDUCTION cannot write");

        // Free4: includes 0x0800 Adaptive ANC!
        Rig free4 = new Rig("OPPO Enco Free4");
        free4.runHandshake(
                capPayload(5, 7, 8, 10),
                pidPayloadLe(OppoProtocol.PID_ENCO_FREE4),
                hex("00 01 06 01"),
                hex("00 01 01 00 08"),
                hex("00 02 01 64 02 64"),
                null,
                null
        );
        check(free4.specs.get(FeatureId.ANC_LEVEL).options.length == 5
                && free4.specs.get(FeatureId.ANC_LEVEL).findOption(0x0800) != null
                && Integer.valueOf(0x0800).equals(free4.values.get(FeatureId.ANC_LEVEL)),
                "Free4 supports 0x0800 Adaptive ANC sub-level");
        free4.driver.writeFeature(FeatureId.ANC_LEVEL, 0x0800, null);
        check(Arrays.equals(free4.lastDecoded().payload, hex("01 01 00 08")),
                "Free4 Adaptive 0x0800 writes LE [01 01 00 08]");
        free4.driver.writeFeature(FeatureId.ANC_LEVEL, 0x80, null);
        check(Arrays.equals(free4.lastDecoded().payload, hex("01 01 80")),
                "Smart Dynamic 0x80 writes [01 01 80]");
        free4.driver.writeFeature(FeatureId.ANC_LEVEL, 0x10, null);
        check(Arrays.equals(free4.lastDecoded().payload, hex("01 01 10")),
                "Max 0x10 writes [01 01 10]");
    }

    private static void x2X3TransWindEqSpatialFindAndExclusions() {
        // Enco X3: Trans Vocal 0x0200, Wind 0x1A in 0x810D, EQ 0,1,2,3,7, Spatial grp 47, Find Buds grp 5
        Rig x3 = new Rig("OPPO Enco X3");
        x3.runHandshake(
                capPayload(5, 7, 8, 10, 47),
                pidPayloadLe(OppoProtocol.PID_ENCO_X3),
                hex("00 04 04 01 06 00 11 01 1A 01"),
                hex("00 01 01 00 02"),
                hex("00 02 01 50 02 50"),
                hex("00 02 01 00 02 00"),
                null
        );
        check(x3.mode == MODE_TRANS
                && x3.specs.get(FeatureId.TRANS_MODE) != null
                && Integer.valueOf(0x0200).equals(x3.values.get(FeatureId.TRANS_MODE)),
                "Enco X3 exposes TRANS_MODE and decodes Vocal 0x0200");
        x3.driver.writeFeature(FeatureId.TRANS_MODE, 0x0100, null);
        check(x3.lastDecoded().command == OppoProtocol.CMD_ANC_SET
                && Arrays.equals(x3.lastDecoded().payload, hex("01 01 00 01")),
                "Enco X3 Standard Trans writes [01 01 00 01]");
        x3.driver.writeFeature(FeatureId.TRANS_MODE, 0x0200, null);
        check(Arrays.equals(x3.lastDecoded().payload, hex("01 01 00 02")),
                "Enco X3 Vocal Trans writes [01 01 00 02]");

        // Wind 0x1A enabled because 0x810D returned 0x1A
        check(x3.specs.get(FeatureId.WIND_REDUCTION) != null
                && Integer.valueOf(1).equals(x3.values.get(FeatureId.WIND_REDUCTION)),
                "Wind 0x1A exposed when returned by 0x810D");
        x3.driver.writeFeature(FeatureId.WIND_REDUCTION, 0, null);
        check(x3.lastDecoded().command == OppoProtocol.CMD_FEATURE_SET
                && Arrays.equals(x3.lastDecoded().payload, hex("1A 00")),
                "Wind writes 0x0403 [1A 00]");

        // EQ grp 10 + PID table (0,1,2,3,7)
        check(x3.specs.get(FeatureId.EQUALIZER) != null
                && x3.driver.equalizerPresets().length == 5
                && x3.specs.get(FeatureId.EQUALIZER).findOption(7) != null,
                "Enco X3 EQ preset table includes 0,1,2,3,7");
        x3.driver.queryFeature(FeatureId.EQUALIZER);
        check(x3.lastDecoded().command == OppoProtocol.CMD_EQ_QUERY, "queryFeature(EQUALIZER) sends 0x010F");
        x3.receiveCmd(OppoProtocol.RSP_EQ, 0x12, hex("00 07"));
        check(Integer.valueOf(7).equals(x3.eq) && (x3.driver.caps() & CAP_EQUALIZER) != 0,
                "0x810F confirms EQ preset 7");
        x3.driver.writeFeature(FeatureId.EQUALIZER, 3, null);
        check(x3.lastDecoded().command == OppoProtocol.CMD_EQ_SET
                && Arrays.equals(x3.lastDecoded().payload, hex("03")),
                "EQUALIZER writes 0x0406 [03]");
        x3.receiveCmd(OppoProtocol.CMD_EQ_NOTIFY, 0xFF, hex("03"));
        check(Integer.valueOf(3).equals(x3.eq), "0x0504 notify updates EQ");

        // Spatial grp 47 (0x012A / 0x0422 / 0x0510)
        FeatureDescriptor sp47 = x3.specs.get(FeatureId.SPATIAL_AUDIO);
        check(sp47 != null && sp47.verif == VERIF_UNTESTED && sp47.devOnly
                && sp47.safety == FeatureDescriptor.SAFETY_AUDIO_WARN
                && sp47.options.length == 3,
                "Spatial grp 47 is VERIF_UNTESTED, devOnly, SAFETY_AUDIO_WARN, 0/1/2");
        x3.driver.queryFeature(FeatureId.SPATIAL_AUDIO);
        check(x3.lastDecoded().command == OppoProtocol.CMD_SPATIAL_QUERY, "Spatial grp 47 queries 0x012A");
        x3.receiveCmd(OppoProtocol.RSP_SPATIAL, 0x15, hex("00 02"));
        check(Integer.valueOf(2).equals(x3.values.get(FeatureId.SPATIAL_AUDIO)), "0x812A sets mode 2");
        x3.driver.writeFeature(FeatureId.SPATIAL_AUDIO, 1, null);
        check(x3.lastDecoded().command == OppoProtocol.CMD_SPATIAL_47_SET
                && Arrays.equals(x3.lastDecoded().payload, hex("01")),
                "Spatial grp 47 writes 0x0422 [01]");
        x3.receiveCmd(OppoProtocol.CMD_SPATIAL_NOTIFY, 0xFF, hex("00"));
        check(Integer.valueOf(0).equals(x3.values.get(FeatureId.SPATIAL_AUDIO)), "0x0510 updates spatial");

        // Find My Buds grp 5 (0x0400)
        FeatureDescriptor find = x3.specs.get(FeatureId.FIND_MY_BUDS);
        check(find != null && find.verif == VERIF_UNTESTED && find.safety == FeatureDescriptor.SAFETY_FIND_BUDS,
                "Find Buds grp 5 is VERIF_UNTESTED with SAFETY_FIND_BUDS");
        x3.driver.writeFeature(FeatureId.FIND_MY_BUDS, 3, null);
        check(x3.lastDecoded().command == OppoProtocol.CMD_FIND_BUDS
                && Arrays.equals(x3.lastDecoded().payload, hex("01")),
                "Find Buds start sends 0x0400 [01]");
        x3.receiveCmd(OppoProtocol.RSP_FIND_BUDS_ACK, 0x18, hex("00"));
        check(Integer.valueOf(3).equals(x3.values.get(FeatureId.FIND_MY_BUDS)), "0x8400 ack confirms ring");
        x3.driver.writeFeature(FeatureId.FIND_MY_BUDS, 0, null);
        check(x3.lastDecoded().command == OppoProtocol.CMD_FIND_BUDS
                && Arrays.equals(x3.lastDecoded().payload, hex("00")),
                "Find Buds stop sends 0x0400 [00]");

        // Spatial grp 43 path (0x0403 [1B] + 0x041E) on Enco X2
        Rig x2 = new Rig("OPPO Enco X2");
        x2.runHandshake(
                capPayload(7, 8, 10, 43),
                pidPayloadLe(OppoProtocol.PID_ENCO_X2),
                hex("00 02 06 01 1B 00"),
                hex("00 01 01 00 01"),
                hex("00 02 01 50 02 50"),
                null,
                null
        );
        check(x2.specs.get(FeatureId.TRANS_MODE) != null
                && Integer.valueOf(0x0100).equals(x2.values.get(FeatureId.TRANS_MODE)),
                "Enco X2 exposes TRANS_MODE Standard 0x0100");
        FeatureDescriptor sp43 = x2.specs.get(FeatureId.SPATIAL_AUDIO);
        check(sp43 != null && sp43.kind == FeatureDescriptor.Kind.TOGGLE
                && sp43.verif == VERIF_UNTESTED && sp43.devOnly
                && sp43.safety == FeatureDescriptor.SAFETY_AUDIO_WARN
                && Integer.valueOf(0).equals(x2.values.get(FeatureId.SPATIAL_AUDIO)),
                "Spatial grp 43 uses 0x810D 0x1B toggle with safety/devOnly flags");
        int beforeSp43 = x2.frames.size();
        x2.driver.writeFeature(FeatureId.SPATIAL_AUDIO, 1, null);
        check(x2.frames.size() == beforeSp43 + 2, "Spatial grp 43 sends both 0x0403 [1B] and 0x041E");
        byte[] f1 = x2.frames.get(beforeSp43);
        byte[] f2 = x2.frames.get(beforeSp43 + 1);
        OppoProtocol.Frame df1 = new OppoProtocol.Decoder().feed(f1, f1.length).get(0);
        OppoProtocol.Frame df2 = new OppoProtocol.Decoder().feed(f2, f2.length).get(0);
        check(df1.command == OppoProtocol.CMD_FEATURE_SET && Arrays.equals(df1.payload, hex("1B 01"))
                && df2.command == OppoProtocol.CMD_SPATIAL_43_SET && Arrays.equals(df2.payload, hex("01")),
                "Spatial grp 43 writes 0x0403 [1B 01] + 0x041E [01]");

        // Combined bitmask (generic 0x02 ORed with Max 0x10) still reports ANC_LEVEL = 0x10
        x3.receiveCmd(OppoProtocol.RSP_ANC, 0x19, hex("00 01 01 12 00"));
        check(x3.mode == MODE_ANC && Integer.valueOf(0x10).equals(x3.values.get(FeatureId.ANC_LEVEL)),
                "combined bitmask 0x0012 extracts Max ANC level 0x10 alongside WIND_REDUCTION");

        // Unlisted non-realme PID reporting multi-level ANC mask dynamically confirms ANC_LEVEL
        Rig unlisted = new Rig("OPPO Enco Air4 Pro");
        unlisted.runHandshake(
                capPayload(5, 7, 8),
                pidPayloadLe(0x06AA10),
                hex("00 01 1A 01"),
                hex("00 01 01 20 00"),
                hex("00 02 01 60 02 60"),
                null,
                null
        );
        check(unlisted.specs.get(FeatureId.WIND_REDUCTION) != null
                && unlisted.specs.get(FeatureId.ANC_LEVEL) != null
                && Integer.valueOf(0x20).equals(unlisted.values.get(FeatureId.ANC_LEVEL)),
                "unlisted OPPO PID dynamically confirms ANC_LEVEL alongside WIND_REDUCTION");

        // Realme 064C12 MUST be excluded from unified ANC mask
        Rig realme = new Rig("realme Buds Air 7 Pro");
        realme.runHandshake(
                capPayload(5, 7, 8, 10),
                pidPayloadLe(OppoProtocol.PID_REALME_AIR7_PRO),
                hex("00 02 06 01 1A 01"),
                hex("00 01 01 08 00"),
                hex("00 02 01 50 02 50"),
                null,
                null
        );
        check(realme.specs.get(FeatureId.ANC_LEVEL) == null && realme.specs.get(FeatureId.EQUALIZER) == null,
                "Realme 064C12 excluded from unified ANC_LEVEL and unmapped EQ table");
        check(realme.specs.get(FeatureId.WIND_REDUCTION) != null,
                "Realme 064C12 still exposes 0x810D-confirmed WIND_REDUCTION");
    }

    private static void sequenceWrapAndBannedOpcodes() {
        for (int banned : new int[]{0x0F00, 0xEF01, 0xEF05, 0xEF09, 0x0405, 0x040B}) {
            check(OppoProtocol.isBannedOpcode(banned), "banned opcode flagged 0x" + Integer.toHexString(banned));
            boolean rejected = false;
            try {
                OppoProtocol.encode(banned, 1, null);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "encoder rejects banned opcode 0x" + Integer.toHexString(banned));
        }

        // Verify nextSeq wraps cleanly avoiding 0xFF and 0x00, while queryBattery/Noise/changeNoise keep 0xF0/F1/F3
        Rig seqRig = new Rig("OPPO Enco Free4");
        seqRig.runHandshake(
                capPayload(5, 7, 8),
                pidPayloadLe(OppoProtocol.PID_ENCO_FREE4),
                hex("00 01 06 01"),
                hex("00 01 01 10 00"),
                hex("00 02 01 50 02 50"),
                null,
                null
        );
        for (int i = 0; i < 260; i++) {
            seqRig.driver.queryFeature(FeatureId.LOW_LATENCY);
            OppoProtocol.Frame f = seqRig.lastDecoded();
            check(f.sequence != 0xFF && f.sequence != 0x00,
                    "new feature commands never use seq 0xFF or 0x00 (got " + f.sequence + ")");
            check(!OppoProtocol.isBannedOpcode(f.command), "no banned opcode emitted");
        }
        seqRig.driver.queryBattery();
        check(seqRig.lastDecoded().sequence == 0xF0, "queryBattery retains golden seq 0xF0");
        seqRig.driver.queryNoise();
        check(seqRig.lastDecoded().sequence == 0xF1, "queryNoise retains golden seq 0xF1");
        seqRig.driver.changeNoise(MODE_TRANS, () -> { });
        check(seqRig.lastDecoded().sequence == 0xF3, "changeNoise retains golden seq 0xF3");
    }

    public static void main(String[] args) {
        discoveryAndDecoders();
        air5ProAndFree4AncAndSwitches();
        x2X3TransWindEqSpatialFindAndExclusions();
        sequenceWrapAndBannedOpcodes();
        System.out.println("PASS " + checks + " OPPO extension checks");
    }
}

package com.hdz.wearcontrol;

import com.hdz.wearcontrol.vivo.VivoGaiaClient;
import com.hdz.wearcontrol.vivo.VivoProtocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static com.hdz.wearcontrol.Controls.*;

/** Comprehensive pure-JVM tests for Wave 3 vivo / iQOO GAIA protocol and driver extensions. */
public final class VivoExtChecks {
    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    private static byte[] hex(String value) {
        String clean = value.replace(" ", "");
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static final class Rig implements WireDriver.Port, Controls.Listener {
        final WireDriver driver;
        final CoreChecks.Clock clock = new CoreChecks.Clock();
        final List<byte[]> frames = new ArrayList<>();
        final List<Runnable> flushes = new ArrayList<>();
        final EnumMap<FeatureId, FeatureDescriptor> specs = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, Integer> values = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, String> texts = new EnumMap<>(FeatureId.class);
        final List<String> valueLog = new ArrayList<>();
        int connections, mode = -1;
        Boolean spatial;
        Integer eq;

        Rig(String name) {
            DriverModule mod = null;
            for (DriverModule m : InstalledDrivers.ALL) {
                if (m.matches(name)) {
                    mod = m;
                    break;
                }
            }
            if (mod == null) throw new AssertionError("Missing vivo driver for " + name);
            driver = mod.create();
            driver.bind(name, this, this);
        }

        void open() { driver.onOpen(); }
        void receive(String value) { driver.received(hex(value)); }
        void receive(byte[] value) { driver.received(value); }

        boolean hasCommand(int cmd) {
            for (byte[] f : frames) {
                if (f.length >= 8 && ((f[6] & 0xFF) << 8 | (f[7] & 0xFF)) == cmd) return true;
            }
            return false;
        }

        byte[] findCommand(int cmd) {
            for (byte[] f : frames) {
                if (f.length >= 8 && ((f[6] & 0xFF) << 8 | (f[7] & 0xFF)) == cmd) return f;
            }
            return null;
        }

        void last(String expected) {
            check(!frames.isEmpty() && Arrays.equals(frames.get(frames.size() - 1), hex(expected)),
                    "expected frame " + expected);
        }

        void flushLast() {
            Runnable r = flushes.set(flushes.size() - 1, null);
            if (r != null) r.run();
        }

        void assertNoBannedCommands() {
            for (byte[] f : frames) {
                if (f.length >= 8) {
                    int cmd = ((f[6] & 0xFF) << 8) | (f[7] & 0xFF);
                    check(cmd != 0x014D && cmd != 0x014A && cmd != 0x014C && cmd != 0x0220,
                            "never transmit banned/falsified cmd 0x" + Integer.toHexString(cmd));
                }
            }
        }

        @Override public void send(byte[] bytes, Runnable flushed) {
            frames.add(bytes.clone());
            flushes.add(flushed);
        }
        @Override public void after(Runnable work, long delay) { clock.after(work, delay); }
        @Override public void cancel(Runnable work) { clock.cancel(work); }
        @Override public void useBle(UUID svc, UUID n, UUID w, long d, long t) { }
        @Override public void fail(String message) { }
        @Override public long millis() { return clock.now; }
        @Override public void trace(String tag, String message) { }
        @Override public void onConnected(String name) { connections++; }
        @Override public void onDisconnected(String reason) { }
        @Override public void onError(String msg) { }
        @Override public void onBattery(int l, int r, int b, int f) { }
        @Override public void onNoiseMode(int m) { mode = m; }
        @Override public void onSpatial(Boolean state) { spatial = state; }
        @Override public void onDeepX(Integer m) { eq = m; }
        @Override public void onEqualizer(Integer m) { eq = m; }
        @Override public void onFeatureSpec(FeatureDescriptor spec) { specs.put(spec.id, spec); }
        @Override public void onFeatureValue(FeatureId id, int value) {
            values.put(id, value);
            valueLog.add(id.name() + "=" + value);
        }
        @Override public void onFeatureText(FeatureId id, String text) { texts.put(id, text); }
    }

    private static void notificationRegistrationAndProbes() {
        for (String name : new String[]{"vivo TWS Air3 Pro", "vivo TWS 3e", "vivo TWS 4"}) {
            Rig r = new Rig(name);
            r.open();
            check(r.connections == 1, name + " opens cleanly");
            for (int reg = 0x0202; reg <= 0x0206; reg++) {
                byte[] f = r.findCommand(reg);
                check(f != null && f[1] == 0x04 && f[3] == 0x00 && f[4] == 0x00 && f[5] == 0x1B,
                        name + " sends v4 empty notification reg 0x" + Integer.toHexString(reg));
            }
            for (int probe : new int[]{0x020D, 0x0251, 0x021C, 0x0249}) {
                byte[] f = r.findCommand(probe);
                boolean expectV4 = name.contains("TWS 4");
                check(f != null && f[1] == (expectV4 ? 0x04 : 0x03)
                        && f[3] == (expectV4 ? 0x01 : 0x00),
                        name + " sends probe 0x" + Integer.toHexString(probe));
            }
            // Also verify 0x0203 wear detect probe vs reg chain.
            int count0203 = 0;
            for (byte[] f : r.frames) {
                if (f.length >= 8 && (((f[6] & 0xFF) << 8) | (f[7] & 0xFF)) == 0x0203) count0203++;
            }
            check(count0203 == 2, name + " sends both 0x0203 reg and 0x0203 probe");
            r.assertNoBannedCommands();
        }

        // TWS 5 skips 0x0202..0x0206 notification registration chain!
        for (String name : new String[]{"vivo TWS 5", "vivo TWS 5 Hi-Fi", "vivo TWS 5 Pro"}) {
            Rig tws5 = new Rig(name);
            tws5.open();
            for (int reg : new int[]{0x0202, 0x0204, 0x0205, 0x0206}) {
                check(!tws5.hasCommand(reg), name + " skips notification reg 0x" + Integer.toHexString(reg));
            }
            byte[] q0203 = tws5.findCommand(0x0203);
            check(q0203 != null && q0203[1] == 0x04 && q0203[3] == 0x01 && q0203[8] == 0x00,
                    name + " sends only v4 [00] wear-detect query on 0x0203");
            tws5.assertNoBannedCommands();
        }
    }

    private static void wearStatusDecoding() {
        check(Integer.valueOf(DeviceState.WEAR_BOTH).equals(VivoProtocol.wearStatus(hex("00 0C"))),
                "0x0C maps to WEAR_BOTH (3)");
        check(Integer.valueOf(DeviceState.WEAR_LEFT).equals(VivoProtocol.wearStatus(hex("00 04"))),
                "0x04 maps to WEAR_LEFT (1)");
        check(Integer.valueOf(DeviceState.WEAR_RIGHT).equals(VivoProtocol.wearStatus(hex("00 08"))),
                "0x08 maps to WEAR_RIGHT (2)");
        check(Integer.valueOf(DeviceState.WEAR_LEFT).equals(VivoProtocol.wearStatus(hex("00 06"))),
                "0x06 (L worn + R in case) maps to WEAR_LEFT (1)");
        check(Integer.valueOf(DeviceState.WEAR_NONE).equals(VivoProtocol.wearStatus(hex("00 03"))),
                "0x03 (both in case) maps to WEAR_NONE (0)");
        check(Integer.valueOf(DeviceState.WEAR_NONE).equals(VivoProtocol.wearStatus(hex("00 00"))),
                "0x00 (both out) maps to WEAR_NONE (0)");
        check(VivoProtocol.wearStatus(hex("01 0C")) == null && VivoProtocol.wearStatus(hex("00")) == null
                && VivoProtocol.wearStatus(null) == null,
                "invalid/short 0x820D wear payloads rejected");

        Rig r = new Rig("vivo TWS Air3 Pro");
        r.open();
        check(!r.specs.containsKey(FeatureId.WEAR_STATUS), "WEAR_STATUS unconfirmed before 0x820D");
        r.receive("FF 03 00 02 00 1B 82 0D 00 0C");
        FeatureDescriptor spec = r.specs.get(FeatureId.WEAR_STATUS);
        check(spec != null && spec.kind == FeatureDescriptor.Kind.READ_ONLY
                && spec.verif == VERIF_UNTESTED && !spec.devOnly,
                "0x820D confirms read-only WEAR_STATUS descriptor");
        check(Integer.valueOf(DeviceState.WEAR_BOTH).equals(r.values.get(FeatureId.WEAR_STATUS)),
                "WEAR_STATUS reports WEAR_BOTH");
        r.receive("FF 03 00 02 00 1B 82 0D 00 03");
        check(Integer.valueOf(DeviceState.WEAR_NONE).equals(r.values.get(FeatureId.WEAR_STATUS)),
                "WEAR_STATUS updates to WEAR_NONE when placed in case");
    }

    private static void lowLatencyGatingAndWrite() {
        Rig r = new Rig("vivo TWS Air3 Pro");
        r.open();
        int before = r.frames.size();
        r.driver.writeFeature(FeatureId.LOW_LATENCY, 1, () -> { });
        check(r.frames.size() == before && !r.specs.containsKey(FeatureId.LOW_LATENCY),
                "LOW_LATENCY blocked until 0x8251 report arrives");

        // 0x8151 write ACK must NOT confirm LOW_LATENCY capability.
        r.receive("FF 03 00 02 00 1B 81 51 00 01");
        check(!r.specs.containsKey(FeatureId.LOW_LATENCY) && r.values.get(FeatureId.LOW_LATENCY) == null,
                "0x8151 ACK alone cannot expose LOW_LATENCY");

        // Valid 0x8251 report exposes toggle and initial state.
        r.receive("FF 03 00 02 00 1B 82 51 00 00");
        FeatureDescriptor spec = r.specs.get(FeatureId.LOW_LATENCY);
        check(spec != null && spec.kind == FeatureDescriptor.Kind.TOGGLE
                && spec.verif == VERIF_UNTESTED && spec.safety == FeatureDescriptor.SAFETY_NONE && !spec.devOnly,
                "0x8251 exposes LOW_LATENCY toggle");
        check(Integer.valueOf(0).equals(r.values.get(FeatureId.LOW_LATENCY)), "initial low latency off");

        final boolean[] flushed = {false};
        r.driver.writeFeature(FeatureId.LOW_LATENCY, 1, () -> flushed[0] = true);
        r.last("FF 03 00 01 00 1B 01 51 01");
        check(!flushed[0], "flushed callback waits for transport flush");
        r.flushLast();
        check(flushed[0], "flushed callback runs on transport flush");

        // TWS 4 uses v4 framing for 0x0151.
        Rig tws4 = new Rig("vivo TWS 4");
        tws4.open();
        tws4.receive("FF 04 00 02 00 1B 82 51 00 01");
        tws4.driver.writeFeature(FeatureId.LOW_LATENCY, 0, () -> { });
        tws4.last("FF 04 00 01 00 1B 01 51 00");
    }

    private static void wearDetectionDisambiguation() {
        check(VivoProtocol.wearDetection(new byte[0]) == null, "empty 0x8203 registration ACK ignored");
        check(VivoProtocol.wearDetection(hex("00")) == null, "1-byte 0x8203 registration ACK ignored");
        check(VivoProtocol.wearDetection(hex("00 01 00")) == null, "3-byte 0x8203 payload ignored");
        check(VivoProtocol.wearDetection(hex("01 01")) == null, "error 0x8203 rejected");
        check(VivoProtocol.wearDetection(hex("00 02")) == null, "out-of-range 0x8203 rejected");
        check(Integer.valueOf(0).equals(VivoProtocol.wearDetection(hex("00 00"))), "2-byte 0x8203 off");
        check(Integer.valueOf(1).equals(VivoProtocol.wearDetection(hex("00 01"))), "2-byte 0x8203 on");

        Rig r = new Rig("vivo TWS 3e");
        r.open();
        // Registration ACKs on 0x8203 (empty & 1-byte) must NOT expose WEAR_DETECTION.
        r.receive("FF 04 00 00 00 1B 82 03");
        r.receive("FF 04 00 01 00 1B 82 03 00");
        r.receive("FF 03 00 02 00 1B 81 03 00 01");
        check(!r.specs.containsKey(FeatureId.WEAR_DETECTION),
                "reg ACKs and 0x8103 do not prematurely expose WEAR_DETECTION");

        r.receive("FF 03 00 02 00 1B 82 03 00 01");
        FeatureDescriptor spec = r.specs.get(FeatureId.WEAR_DETECTION);
        check(spec != null && spec.kind == FeatureDescriptor.Kind.TOGGLE
                && Integer.valueOf(1).equals(r.values.get(FeatureId.WEAR_DETECTION)),
                "2-byte 0x8203 exposes WEAR_DETECTION=1");

        r.driver.writeFeature(FeatureId.WEAR_DETECTION, 0, () -> { });
        r.last("FF 03 00 01 00 1B 01 03 00");
    }

    private static void findMyBuds() {
        for (String name : new String[]{"vivo TWS Air3 Pro", "vivo TWS 3e", "vivo TWS 4", "vivo TWS 5"}) {
            Rig r = new Rig(name);
            r.open();
            FeatureDescriptor spec = r.specs.get(FeatureId.FIND_MY_BUDS);
            check(spec != null && spec.kind == FeatureDescriptor.Kind.ACTION_TARGET
                    && spec.safety == FeatureDescriptor.SAFETY_FIND_BUDS
                    && spec.options.length == 1 && spec.options[0].id == 3
                    && spec.isValidValue(0) && spec.isValidValue(3)
                    && !spec.isValidValue(1) && !spec.isValidValue(2),
                    name + " exposes Find My Buds with single Ring Both (3) option");

            int before = r.frames.size();
            r.driver.writeFeature(FeatureId.FIND_MY_BUDS, 1, () -> { });
            r.driver.writeFeature(FeatureId.FIND_MY_BUDS, 2, () -> { });
            check(r.frames.size() == before, name + " blocks unverified per-ear ring targets");

            boolean v4 = name.contains("TWS 4") || name.contains("TWS 5");
            String hdr = v4 ? "FF040001001B0120" : "FF030001001B0120";
            r.driver.writeFeature(FeatureId.FIND_MY_BUDS, 3, () -> { });
            r.last(hdr + "01");
            r.receive("FF030002001B81200001");
            check(Integer.valueOf(3).equals(r.values.get(FeatureId.FIND_MY_BUDS)),
                    name + " 0x8120 [00,01] reports target 3");

            r.driver.writeFeature(FeatureId.FIND_MY_BUDS, 0, () -> { });
            r.last(hdr + "00");
            r.receive("FF030002001B81200000");
            check(Integer.valueOf(0).equals(r.values.get(FeatureId.FIND_MY_BUDS)),
                    name + " 0x8120 [00,00] reports stop 0");

            // queryFeature(FIND_MY_BUDS) must never send falsified 0x0220.
            int qBefore = r.frames.size();
            r.driver.queryFeature(FeatureId.FIND_MY_BUDS);
            check(r.frames.size() == qBefore, name + " queryFeature(FIND_MY_BUDS) sends no 0x0220");
            r.assertNoBannedCommands();
        }

        Rig generic = new Rig("vivo Buds");
        generic.open();
        check(!generic.specs.containsKey(FeatureId.FIND_MY_BUDS),
                "unlisted vivo model does not expose FIND_MY_BUDS");
    }

    private static void firmwareAndMultipoint() {
        check("2.5.9".equals(VivoProtocol.firmware(hex("00 01 02 03 04 05 09 0C"))),
                "0x821C binary [2].[5].[6] decode");
        check("1.4.0".equals(VivoProtocol.firmware(hex("00 00 01 00 00 04 00"))),
                "0x821C binary zero patch decode");
        check("2.5.9".equals(VivoProtocol.firmware(hex("00 32 2E 35 2E 39"))),
                "0x821C ASCII fallback");
        check("1.2.3.4".equals(VivoProtocol.firmware(hex("00 31 2E 32 2E 33 2E 34"))),
                "0x821C long ASCII does not collide with binary length>=7");
        check("v1.0.3".equals(VivoProtocol.firmware(hex("00 76 31 2E 30 2E 33 00"))),
                "0x821C ASCII trims trailing NUL");
        check(VivoProtocol.firmware(hex("01 01 02 03 04 05 09 0C")) == null
                && VivoProtocol.firmware(hex("00 00 00 00 00 00 00")) == null
                && VivoProtocol.firmware(hex("00 01 02")) == null,
                "invalid/error/short 0x821C rejected");

        // Build 0x8249 payload with 2 devices: [Watch7 state=2] and [Pixel state=0 disconnected]
        byte[] watchName = "Watch7".getBytes(StandardCharsets.UTF_8);
        byte[] pixelName = "Pixel".getBytes(StandardCharsets.UTF_8);
        byte[] mpPayload = new byte[2 + 11 + watchName.length + 11 + pixelName.length];
        mpPayload[0] = 0x00;
        mpPayload[1] = 0x02;
        int p = 2;
        mpPayload[p + 9] = 0x02; // active
        mpPayload[p + 10] = (byte) watchName.length;
        System.arraycopy(watchName, 0, mpPayload, p + 11, watchName.length);
        p += 11 + watchName.length;
        mpPayload[p + 9] = 0x00; // disconnected
        mpPayload[p + 10] = (byte) pixelName.length;
        System.arraycopy(pixelName, 0, mpPayload, p + 11, pixelName.length);

        VivoProtocol.MultipointInfo info = VivoProtocol.multipoint(mpPayload);
        check(info != null && info.totalCount == 2 && info.connectedCount == 1
                && info.connectedNames.equals(Arrays.asList("Watch7")),
                "0x8249 decodes connected count and names");
        check(VivoProtocol.multipoint(hex("00 01 11 22 33")) == null, "truncated 0x8249 rejected");

        Rig r = new Rig("vivo TWS 4");
        r.open();
        r.receive("FF 04 00 08 00 1B 82 1C 00 01 02 03 04 05 09 0C");
        check("2.5.9".equals(r.texts.get(FeatureId.FIRMWARE_INFO))
                && r.specs.containsKey(FeatureId.FIRMWARE_INFO),
                "0x821C reports FIRMWARE_INFO text");
        r.receive(VivoProtocol.frame(0x04, VivoGaiaClient.VENDOR_VIVO, VivoGaiaClient.RESP_MULTIPOINT, mpPayload));
        String combined = r.texts.get(FeatureId.FIRMWARE_INFO);
        check(combined != null && combined.contains("2.5.9") && combined.contains("Watch7")
                && !combined.contains("Pixel"),
                "0x8249 appends read-only connected device summary to FIRMWARE_INFO");
        r.assertNoBannedCommands();
    }

    private static void eqAndSpatialVariants() {
        // Air3 Pro: 0/1/2/3/5 VERIF_REAL, devOnly=false, slot 4 skipped; spatial toggle-only with honest limit.
        Rig air3 = new Rig("vivo TWS Air3 Pro");
        air3.open();
        air3.receive("FF 03 00 02 00 1B 82 18 00 05");
        FeatureDescriptor eqSpec = air3.specs.get(FeatureId.EQUALIZER);
        check(eqSpec != null && eqSpec.verif == VERIF_REAL && !eqSpec.devOnly
                && eqSpec.options.length == 5 && !eqSpec.isValidValue(4) && eqSpec.isValidValue(5),
                "Air3 Pro EQUALIZER is VERIF_REAL devOnly=false with presets 0/1/2/3/5");
        int beforeEq = air3.frames.size();
        air3.driver.writeFeature(FeatureId.EQUALIZER, 4, () -> { });
        check(air3.frames.size() == beforeEq, "Air3 Pro skips DeepX slot 4");
        air3.driver.writeFeature(FeatureId.EQUALIZER, 2, () -> { });
        air3.last("FF 03 00 01 00 1B 01 18 02");

        // iQOO TWS Air3 Pro allows preset 4 (Immersive Surround).
        Rig iqoo = new Rig("iQOO TWS Air3 Pro");
        iqoo.open();
        iqoo.receive("FF 03 00 02 00 1B 82 18 00 04");
        FeatureDescriptor iqooEq = iqoo.specs.get(FeatureId.EQUALIZER);
        check(Integer.valueOf(4).equals(iqoo.eq) && iqooEq != null && iqooEq.options.length == 6
                && iqooEq.isValidValue(4),
                "iQOO TWS Air3 Pro accepts DeepX preset 4");
        iqoo.driver.writeFeature(FeatureId.EQUALIZER, 4, () -> { });
        iqoo.last("FF 03 00 01 00 1B 01 18 04");

        // Air3 Pro spatial rejects mode=2 and sets explicit honest limitation.
        air3.receive("FF 03 00 02 00 1B 82 39 00 02");
        check(air3.spatial == null && !air3.specs.containsKey(FeatureId.SPATIAL_AUDIO),
                "Air3 Pro rejects spatial mode 2");
        air3.receive("FF 03 00 03 00 1B 82 39 00 01 01");
        FeatureDescriptor air3Sp = air3.specs.get(FeatureId.SPATIAL_AUDIO);
        check(air3Sp != null && air3Sp.verif == VERIF_UNTESTED && air3Sp.devOnly
                && air3Sp.safety == FeatureDescriptor.SAFETY_AUDIO_WARN
                && "仅基础开关；Air3 Pro 硬件不支持场景与头追".equals(air3Sp.limitZh)
                && "Toggle only; Air3 Pro hardware lacks scenes & head tracking".equals(air3Sp.limitEn)
                && !air3.specs.containsKey(FeatureId.HEAD_TRACKING),
                "Air3 Pro spatial carries explicit hardware limitation and no HEAD_TRACKING");

        // TWS 5 supports 0=Off, 1=Fixed, 2=Head Tracking.
        Rig tws5 = new Rig("vivo TWS 5 Hi-Fi");
        tws5.open();
        tws5.receive("FF 04 00 02 00 1B 82 39 00 02");
        check(Boolean.TRUE.equals(tws5.spatial)
                && tws5.specs.containsKey(FeatureId.SPATIAL_AUDIO)
                && tws5.specs.containsKey(FeatureId.HEAD_TRACKING)
                && Integer.valueOf(1).equals(tws5.values.get(FeatureId.HEAD_TRACKING)),
                "TWS 5 0x8239 [00, 02] confirms SPATIAL_AUDIO=1 and HEAD_TRACKING=1");

        final boolean[] htFlushed = {false};
        tws5.driver.writeFeature(FeatureId.HEAD_TRACKING, 0, () -> htFlushed[0] = true);
        tws5.last("FF 04 00 01 00 1B 01 39 01");
        tws5.flushLast();
        check(htFlushed[0], "TWS 5 HEAD_TRACKING=0 sends 0x0139 [01] Fixed and runs flush");

        tws5.driver.writeFeature(FeatureId.HEAD_TRACKING, 1, () -> { });
        tws5.last("FF 04 00 01 00 1B 01 39 02");
        tws5.driver.writeFeature(FeatureId.SPATIAL_AUDIO, 0, () -> { });
        tws5.last("FF 04 00 01 00 1B 01 39 00");

        tws5.receive("FF 04 00 02 00 1B 82 39 00 01");
        check(Boolean.TRUE.equals(tws5.spatial)
                && Integer.valueOf(0).equals(tws5.values.get(FeatureId.HEAD_TRACKING)),
                "TWS 5 0x8239 [00, 01] reports SPATIAL_AUDIO=1 and HEAD_TRACKING=0");

        // Zero regression on 0x0130 ANC write.
        tws5.receive("FF 04 00 04 00 1B 82 30 00 00 03 01");
        check(tws5.mode == MODE_ANC, "TWS 5 ANC confirmed");
        tws5.driver.setNoiseMode(MODE_TRANS);
        tws5.last("FF 04 00 03 00 1B 01 30 02 03 01");
    }

    public static void main(String[] args) {
        notificationRegistrationAndProbes();
        wearStatusDecoding();
        lowLatencyGatingAndWrite();
        wearDetectionDisambiguation();
        findMyBuds();
        firmwareAndMultipoint();
        eqAndSpatialVariants();
        System.out.println("PASS " + checks + " vivo extension checks");
    }
}

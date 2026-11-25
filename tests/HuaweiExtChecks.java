package com.hdz.wearcontrol;

import com.hdz.wearcontrol.huawei.HuaweiProtocol;
import com.hdz.wearcontrol.huawei.HuaweiSoundProtocol;
import com.hdz.wearcontrol.huawei.HuaweiSppClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static com.hdz.wearcontrol.Controls.*;

/** Unit tests for Wave 3 Huawei / Honor driver extensions and safety gating. */
public final class HuaweiExtChecks {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static byte[] hex(String s) {
        return ProtocolChecks.hex(s);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private static byte[] huaweiFrame(int group, int cmd, byte[] params) {
        return HuaweiProtocol.frame(group, cmd, params);
    }

    private static final class Rig implements WireDriver.Port, Controls.Listener {
        final WireDriver driver;
        final CoreChecks.Clock clock = new CoreChecks.Clock();
        final List<byte[]> frames = new ArrayList<>();
        final List<Runnable> completions = new ArrayList<>();
        final EnumMap<FeatureId, FeatureDescriptor> specs = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, Integer> values = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, String> texts = new EnumMap<>(FeatureId.class);
        int mode = -1;
        Integer eq;
        Boolean spatial;

        Rig(String name) {
            DriverModule module = null;
            for (DriverModule m : InstalledDrivers.ALL) {
                if (m.matches(name)) {
                    module = m;
                    break;
                }
            }
            if (module == null) throw new AssertionError("Missing driver for " + name);
            driver = module.create();
            driver.bind(name, this, this);
        }

        void open() { driver.onOpen(); }

        void receive(byte[] bytes) { driver.received(bytes); }

        void receive(String hexStr) { driver.received(hex(hexStr)); }

        boolean hasSent(String expectedHex) {
            return hasSent(hex(expectedHex));
        }

        boolean hasSent(byte[] target) {
            for (byte[] f : frames) {
                if (Arrays.equals(f, target)) return true;
            }
            return false;
        }

        boolean hasSentGroupCmd(int group, int cmd) {
            for (byte[] f : frames) {
                if (f.length >= 6 && (f[4] & 0xFF) == group && (f[5] & 0xFF) == cmd) return true;
            }
            return false;
        }

        void last(String expectedHex) {
            last(hex(expectedHex));
        }

        void last(byte[] target) {
            check(!frames.isEmpty() && Arrays.equals(frames.get(frames.size() - 1), target),
                    "expected frame " + toHex(target) + ", got " +
                            (frames.isEmpty() ? "(none)" : toHex(frames.get(frames.size() - 1))));
        }

        void flushLast() {
            int i = completions.size() - 1;
            Runnable r = completions.set(i, null);
            if (r != null) r.run();
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
        @Override public void onConnected(String deviceName) { }
        @Override public void onDisconnected(String reason) { }
        @Override public void onError(String msg) { }
        @Override public void onBattery(int l, int r, int b, int c) { }
        @Override public void onNoiseMode(int mode) { this.mode = mode; }
        @Override public void onSpatial(Boolean state) { this.spatial = state; }
        @Override public void onDeepX(Integer mode) { this.eq = mode; }
        @Override public void onEqualizer(Integer mode) { this.eq = mode; }
        @Override public void onFeatureSpec(FeatureDescriptor spec) { specs.put(spec.id, spec); }
        @Override public void onFeatureValue(FeatureId id, int value) { values.put(id, value); }
        @Override public void onFeatureText(FeatureId id, String text) { texts.put(id, text); }
    }

    private static void ancSubLevelsAndTransBoost() {
        // 6i standard ANC level table: 0=General, 1=Cozy, 2=Ultra, 3=Dynamic.
        Rig r6i = new Rig("HUAWEI FreeBuds 6i");
        r6i.open();
        r6i.clock.tick(250);
        r6i.receive(huaweiFrame(0x2B, 0x2A, hex("01 02 02 01"))); // level=2 (Ultra), mode=1 (ANC)
        FeatureDescriptor anc6i = r6i.specs.get(FeatureId.ANC_LEVEL);
        check(anc6i != null && anc6i.verif == VERIF_UNTESTED && !anc6i.devOnly
                && anc6i.allowsNoiseMode(MODE_ANC) && !anc6i.allowsNoiseMode(MODE_TRANS),
                "6i ANC_LEVEL spec confirmed when mode == MODE_ANC");
        check(Integer.valueOf(2).equals(r6i.values.get(FeatureId.ANC_LEVEL)), "6i ANC level decoded as 2 (Ultra)");
        check("Ultra".equals(anc6i.findOption(2).english) && "Cozy".equals(anc6i.findOption(1).english)
                && "General".equals(anc6i.findOption(0).english) && "Dynamic".equals(anc6i.findOption(3).english),
                "6i uses standard ANC level mapping");

        // Write Cozy (1) on 6i -> 2B 04 [01 02 01 01]
        int[] flushed = {0};
        r6i.driver.writeFeature(FeatureId.ANC_LEVEL, 1, () -> flushed[0]++);
        r6i.last("5A0007002B0401020101F13D");
        r6i.flushLast();
        r6i.clock.tick(400);
        r6i.last("5A0007002B2A010002001D33");
        check(flushed[0] == 1, "ANC_LEVEL flush invoked");

        // 6i Trans Voice Boost: 02=Standard, 01=Voice Boost
        r6i.receive(huaweiFrame(0x2B, 0x2A, hex("01 02 02 02"))); // level=2 (Standard), mode=2 (Trans)
        FeatureDescriptor trans6i = r6i.specs.get(FeatureId.TRANS_MODE);
        check(trans6i != null && trans6i.allowsNoiseMode(MODE_TRANS) && !trans6i.allowsNoiseMode(MODE_ANC)
                && Integer.valueOf(2).equals(r6i.values.get(FeatureId.TRANS_MODE)),
                "6i TRANS_MODE confirmed when mode == MODE_TRANS");
        check("Voice Boost".equals(trans6i.findOption(1).english) && "Standard".equals(trans6i.findOption(2).english),
                "6i TRANS_MODE option labels");
        r6i.driver.writeFeature(FeatureId.TRANS_MODE, 1, () -> flushed[0]++);
        r6i.last("5A0007002B0401020201A46E");
        r6i.flushLast();
        r6i.clock.tick(400);
        r6i.last("5A0007002B2A010002001D33");

        // Pro 3 swapped ANC level table: 0=Cozy, 1=Dynamic, 2=General, 3=Ultra; no TRANS_MODE.
        Rig rPro3 = new Rig("HUAWEI FreeBuds Pro 3");
        rPro3.open();
        rPro3.receive(huaweiFrame(0x2B, 0x03, hex("01 02 00 01"))); // 2B 03 push: level=0 (Cozy on Pro 3), mode=1 (ANC)
        FeatureDescriptor ancPro3 = rPro3.specs.get(FeatureId.ANC_LEVEL);
        check(ancPro3 != null && "Cozy".equals(ancPro3.findOption(0).english)
                && "Dynamic".equals(ancPro3.findOption(1).english)
                && "General".equals(ancPro3.findOption(2).english)
                && "Ultra".equals(ancPro3.findOption(3).english),
                "Pro 3 uses swapped ANC level mapping");
        check(Integer.valueOf(0).equals(rPro3.values.get(FeatureId.ANC_LEVEL)), "Pro 3 2B 03 push updates ANC_LEVEL");
        rPro3.receive(huaweiFrame(0x2B, 0x2A, hex("01 02 01 02")));
        check(!rPro3.specs.containsKey(FeatureId.TRANS_MODE), "Pro 3 does not expose 6i/Pro2/Pro5 TRANS_MODE");

        // FreeBuds 7i also uses swapped ANC level table.
        Rig r7i = new Rig("HUAWEI FreeBuds 7i");
        r7i.open();
        r7i.receive(huaweiFrame(0x2B, 0x2A, hex("01 02 01 01")));
        FeatureDescriptor anc7i = r7i.specs.get(FeatureId.ANC_LEVEL);
        check(anc7i != null && "Dynamic".equals(anc7i.findOption(1).english)
                && "General".equals(anc7i.findOption(2).english),
                "7i uses swapped ANC level mapping");

        // FreeBuds Pro 2 has 3 levels (no Dynamic=3).
        Rig rPro2 = new Rig("HUAWEI FreeBuds Pro 2");
        rPro2.open();
        rPro2.receive(huaweiFrame(0x2B, 0x2A, hex("01 02 00 01")));
        FeatureDescriptor ancPro2 = rPro2.specs.get(FeatureId.ANC_LEVEL);
        check(ancPro2 != null && ancPro2.options.length == 3 && !ancPro2.isValidValue(3),
                "Pro 2 excludes Dynamic ANC level 3");

        // Pro 4 and HONOR X9 keep 3-state ANC without exposing ANC_LEVEL.
        Rig rPro4 = new Rig("HUAWEI FreeBuds Pro 4");
        rPro4.open();
        rPro4.receive(huaweiFrame(0x2B, 0x2A, hex("01 02 02 01")));
        check(rPro4.mode == MODE_ANC && !rPro4.specs.containsKey(FeatureId.ANC_LEVEL),
                "Pro 4 keeps 3-state ANC without unverified sub-levels");

        Rig rX9 = new Rig("HONOR Earbuds X9");
        rX9.open();
        rX9.receive(huaweiFrame(0x2B, 0x2A, hex("01 02 01 01")));
        check(rX9.mode == MODE_ANC && !rX9.specs.containsKey(FeatureId.ANC_LEVEL),
                "HONOR Earbuds X9 keeps 3-state ANC without sub-levels");
    }

    private static void windNoiseReduction() {
        Rig se4 = new Rig("HUAWEI FreeBuds SE 4 ANC");
        se4.open();
        se4.clock.tick(250);
        check(se4.hasSent("5A0008002BB401011D0200594B"), "SE 4 ANC probes 2B B4 1D at connect");

        // Error 0x7F reply (not 00 01 86 A0) must NOT expose WIND_REDUCTION.
        se4.receive(huaweiFrame(0x2B, 0xB4, hex("01 01 1D 7F 04 00 01 86 A1")));
        check(!se4.specs.containsKey(FeatureId.WIND_REDUCTION), "0x7F error rejects wind feature");
        int before = se4.frames.size();
        se4.driver.writeFeature(FeatureId.WIND_REDUCTION, 1, null);
        check(se4.frames.size() == before, "unconfirmed wind never writes");

        // Valid reply with 0x7F success (00 01 86 A0) exposes WIND_REDUCTION with devOnly=false.
        se4.receive(huaweiFrame(0x2B, 0xB4, hex("01 01 1D 02 01 00 7F 04 00 01 86 A0")));
        FeatureDescriptor wind = se4.specs.get(FeatureId.WIND_REDUCTION);
        check(wind != null && !wind.devOnly && wind.verif == VERIF_UNTESTED
                && Integer.valueOf(0).equals(se4.values.get(FeatureId.WIND_REDUCTION)),
                "SE 4 ANC valid 2B B4 1D reply exposes WIND_REDUCTION");

        se4.driver.writeFeature(FeatureId.WIND_REDUCTION, 1, null);
        se4.last("5A0009002BB401011D020101CC89");
        se4.flushLast();
        se4.clock.tick(300);
        se4.last("5A0008002BB401011D0200594B");

        se4.driver.writeFeature(FeatureId.WIND_REDUCTION, 0, null);
        se4.last("5A0009002BB401011D020100DCA8");

        // SE 4 ANC with WIND_REDUCTION active and non-standard ANC level (0xFF) must STILL expose ANC_LEVEL!
        se4.receive(huaweiFrame(0x2B, 0x2A, hex("01 02 FF 01")));
        check(se4.mode == MODE_ANC && se4.specs.containsKey(FeatureId.WIND_REDUCTION)
                && se4.specs.containsKey(FeatureId.ANC_LEVEL),
                "SE 4 ANC exposes ANC_LEVEL alongside WIND_REDUCTION even when level byte is 0xFF");

        // Non-supporting model silently stays hidden if device never replies / sends error.
        Rig r6i = new Rig("HUAWEI FreeBuds 6i");
        r6i.open();
        r6i.receive(huaweiFrame(0x2B, 0xB4, hex("01 01 1D 7F 04 00 01 86 A4")));
        check(!r6i.specs.containsKey(FeatureId.WIND_REDUCTION), "non-supporting model stays hidden");
    }

    private static void spatialAudioAndScenes() {
        Rig r = new Rig("HUAWEI FreeBuds Pro 5");
        r.open();
        r.clock.tick(250);
        check(r.hasSent("5A000A002BB4010118020003009B3F"), "Pro 5 probes 2B B4 18 at connect");

        // Error ACK rejected.
        r.receive(huaweiFrame(0x2B, 0xB4, hex("01 01 18 7F 04 00 01 86 A1")));
        check(!r.specs.containsKey(FeatureId.SPATIAL_AUDIO) && !r.specs.containsKey(FeatureId.SPATIAL_SCENE),
                "0x7F error hides spatial audio & scene");

        // Valid reply: mode=2 (Fixed), scene=2 (Cinema).
        r.receive(huaweiFrame(0x2B, 0xB4, hex("01 01 18 02 01 02 03 01 02")));
        FeatureDescriptor sp = r.specs.get(FeatureId.SPATIAL_AUDIO);
        FeatureDescriptor sc = r.specs.get(FeatureId.SPATIAL_SCENE);
        check(sp != null && sp.devOnly && sp.safety == FeatureDescriptor.SAFETY_AUDIO_WARN
                && sp.verif == VERIF_UNTESTED, "SPATIAL_AUDIO descriptor gated devOnly + SAFETY_AUDIO_WARN");
        check("Off".equals(sp.findOption(0).english)
                && "Head Tracking".equals(sp.findOption(1).english)
                && "Fixed".equals(sp.findOption(2).english),
                "Huawei spatial polarity 1=HeadTrack, 2=Fixed");
        check(Integer.valueOf(2).equals(r.values.get(FeatureId.SPATIAL_AUDIO)), "Fixed mode (2) reported");

        check(sc != null && sc.devOnly && sc.safety == FeatureDescriptor.SAFETY_AUDIO_WARN
                && "Default".equals(sc.findOption(0).english)
                && "Theater".equals(sc.findOption(1).english)
                && "Cinema".equals(sc.findOption(2).english)
                && "Concert".equals(sc.findOption(3).english)
                && Integer.valueOf(2).equals(r.values.get(FeatureId.SPATIAL_SCENE)),
                "SPATIAL_SCENE options & Cinema value reported");

        // Write Fixed (2), HeadTrack (1), Off (0), and Scene Concert (3).
        r.driver.writeFeature(FeatureId.SPATIAL_AUDIO, 2, null);
        r.last("5A0009002BB401011802010240AF");
        r.driver.writeFeature(FeatureId.SPATIAL_AUDIO, 1, null);
        r.last("5A0009002BB401011802010170CC");
        r.driver.writeFeature(FeatureId.SPATIAL_AUDIO, 0, null);
        r.last("5A0009002BB401011802010060ED");
        r.driver.writeFeature(FeatureId.SPATIAL_SCENE, 3, null);
        r.last("5A0009002BB401011803010367BE");
    }

    private static void lowLatencyWearAndMultipoint() {
        Rig r = new Rig("HUAWEI FreeBuds Pro 4");
        r.open();
        r.clock.tick(250);
        // Probes sent at connect: 2B 6C (02 00), 2B 11 (01 00), 2B 25 (01 00 02 00), 2B 2F (01 00), 01 07
        check(r.hasSent(huaweiFrame(0x2B, 0x6C, hex("02 00"))), "low-latency probe");
        check(r.hasSent(huaweiFrame(0x2B, 0x11, hex("01 00"))), "wear-detect probe");
        check(r.hasSent(huaweiFrame(0x2B, 0x25, hex("01 00 02 00"))), "live-wear probe");
        check(r.hasSent(huaweiFrame(0x2B, 0x2F, hex("01 00"))), "Pro 4 multipoint probe");
        check(r.hasSent(huaweiFrame(0x01, 0x07, hex("03 00 07 00 0F 00"))), "device info probe");

        // Low latency 2B 6C reply & write + 300ms re-read
        r.receive(huaweiFrame(0x2B, 0x6C, hex("02 01 00")));
        check(Integer.valueOf(0).equals(r.values.get(FeatureId.LOW_LATENCY)), "low-latency decoded as 0");
        r.driver.writeFeature(FeatureId.LOW_LATENCY, 1, null);
        r.last("5A0006002B6C010101A411");
        r.flushLast();
        r.clock.tick(299);
        r.last("5A0006002B6C010101A411");
        r.clock.tick(1);
        r.last(huaweiFrame(0x2B, 0x6C, hex("02 00")));

        // Wear detection 2B 11 / 2B 10
        r.receive(huaweiFrame(0x2B, 0x11, hex("01 01 01")));
        check(Integer.valueOf(1).equals(r.values.get(FeatureId.WEAR_DETECTION)), "wear detect decoded as 1");
        r.driver.writeFeature(FeatureId.WEAR_DETECTION, 0, null);
        r.last("5A0006002B10010100B977");
        r.flushLast();
        r.clock.tick(300);
        r.last(huaweiFrame(0x2B, 0x11, hex("01 00")));

        // Live wear 2B 25 + 2B 03 TLV 8/9
        r.receive(huaweiFrame(0x2B, 0x25, hex("01 01 01 02 01 00")));
        check(Integer.valueOf(DeviceState.WEAR_LEFT).equals(r.values.get(FeatureId.WEAR_STATUS)), "2B 25 L=1 R=0 -> WEAR_LEFT");
        r.receive(huaweiFrame(0x2B, 0x25, hex("01 01 01 02 01 01")));
        check(Integer.valueOf(DeviceState.WEAR_BOTH).equals(r.values.get(FeatureId.WEAR_STATUS)), "2B 25 L=1 R=1 -> WEAR_BOTH");
        r.receive(huaweiFrame(0x2B, 0x03, hex("08 01 00 09 01 01")));
        check(Integer.valueOf(DeviceState.WEAR_RIGHT).equals(r.values.get(FeatureId.WEAR_STATUS)), "2B 03 TLV8=0,9=1 -> WEAR_RIGHT");
        r.receive(huaweiFrame(0x2B, 0x03, hex("09 01 00")));
        check(Integer.valueOf(DeviceState.WEAR_NONE).equals(r.values.get(FeatureId.WEAR_STATUS)), "2B 03 TLV9=0 -> WEAR_NONE");

        // Multipoint 2B 2F / 2B 2E on Pro 4
        r.receive(huaweiFrame(0x2B, 0x2F, hex("01 01 01")));
        check(Integer.valueOf(1).equals(r.values.get(FeatureId.MULTIPOINT)), "Pro 4 multipoint decoded as 1");
        r.driver.writeFeature(FeatureId.MULTIPOINT, 0, null);
        r.last(huaweiFrame(0x2B, 0x2E, hex("01 01 00")));
        r.flushLast();
        r.clock.tick(300);
        r.last(huaweiFrame(0x2B, 0x2F, hex("01 00")));
        check(!r.hasSentGroupCmd(0x2B, 0x33), "never emit 2B 33 unpair");

        // Firmware info 01 07 TLV 7
        r.receive(huaweiFrame(0x01, 0x07, hex("03 03 48 57 31 07 07 35 2E 30 2E 30 2E 31 0F 04 50 72 6F 34")));
        check("5.0.0.1".equals(r.texts.get(FeatureId.FIRMWARE_INFO)), "01 07 TLV 7 decoded as firmware string");

        // HONOR MUST NEVER send 2B 2E / 2B 2F!
        for (String honorName : new String[]{"HONOR Earbuds X9", "荣耀 Earbuds X9"}) {
            Rig honor = new Rig(honorName);
            honor.open();
            check(!honor.hasSentGroupCmd(0x2B, 0x2E) && !honor.hasSentGroupCmd(0x2B, 0x2F),
                    "HONOR never probes 2B 2E/2F: " + honorName);
            honor.receive(huaweiFrame(0x2B, 0x2F, hex("01 01 01")));
            check(!honor.specs.containsKey(FeatureId.MULTIPOINT), "HONOR ignores 2B 2F multipoint");
            int count = honor.frames.size();
            honor.driver.queryFeature(FeatureId.MULTIPOINT);
            honor.driver.writeFeature(FeatureId.MULTIPOINT, 1, null);
            check(honor.frames.size() == count, "HONOR never emits 2B 2E/2F on query/write");
        }
    }

    private static void equalizerModels() {
        check(HuaweiSoundProtocol.supportsQuery("HUAWEI FreeBuds 5i")
                && HuaweiSoundProtocol.supportsQuery("FreeBuds 6i")
                && HuaweiSoundProtocol.supportsQuery("HUAWEI FreeBuds SE 4 ANC")
                && HuaweiSoundProtocol.supportsQuery("FreeBuds Pro 2")
                && HuaweiSoundProtocol.supportsQuery("HUAWEI FreeBuds Pro 3")
                && HuaweiSoundProtocol.supportsQuery("FreeBuds Pro 4"),
                "expanded EQ supportsQuery scope");
        check(!HuaweiSoundProtocol.supportsQuery("HONOR Earbuds X9")
                && !HuaweiSoundProtocol.supportsQuery("FreeBuds 6")
                && !HuaweiSoundProtocol.supportsQuery("FreeBuds Pro 6"),
                "EQ excludes HONOR and unrelated models");

        // Pro 3/4 preset map: 5=Default, 1=Bass, 2=Treble, 9=Vocals
        byte[] pro3EqPayload = hex("01 01 00 02 01 05 03 04 05 01 02 09");
        HuaweiSoundProtocol.State s = HuaweiSoundProtocol.decode(pro3EqPayload, 0, pro3EqPayload.length, "FreeBuds Pro 3");
        check(s != null && s.current == 5 && s.presets.length == 4
                && s.presets[0].id == 5 && "Default".equals(s.presets[0].english)
                && s.presets[1].id == 1 && "Bass".equals(s.presets[1].english)
                && s.presets[2].id == 2 && "Treble".equals(s.presets[2].english)
                && s.presets[3].id == 9 && "Vocals".equals(s.presets[3].english),
                "Pro 3 EQ decodes 5=Default, 1=Bass, 2=Treble, 9=Vocals");

        Rig rPro4 = new Rig("HUAWEI FreeBuds Pro 4");
        rPro4.open();
        rPro4.driver.queryFeature(FeatureId.EQUALIZER);
        rPro4.last("5A0009002B4A010002000300ACA2");
        rPro4.receive(huaweiFrame(0x2B, 0x4A, pro3EqPayload));
        check(Integer.valueOf(5).equals(rPro4.eq) && rPro4.specs.containsKey(FeatureId.EQUALIZER),
                "Pro 4 EQ report confirms EQUALIZER spec and current 5");
        rPro4.driver.writeFeature(FeatureId.EQUALIZER, 5, null);
        rPro4.last(huaweiFrame(0x2B, 0x49, hex("01 01 05")));
    }

    public static void main(String[] args) {
        ancSubLevelsAndTransBoost();
        windNoiseReduction();
        spatialAudioAndScenes();
        lowLatencyWearAndMultipoint();
        equalizerModels();
        System.out.println("PASS " + checks + " Huawei extension checks");
    }
}

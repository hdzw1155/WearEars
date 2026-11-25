package com.hdz.wearcontrol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static com.hdz.wearcontrol.Controls.*;

/** Pure-JVM verification suite for Wave 5 extensions across NiceHCK, Edifier, Sony, Technics, and RoseLink. */
public final class OtherBrandsExtChecks {
    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    private static byte[] hex(String value) {
        return ProtocolChecks.hex(value);
    }

    private static byte[] concat(byte[]... parts) {
        return ProtocolChecks.concat(parts);
    }

    private static final class Rig implements WireDriver.Port, Controls.Listener {
        final WireDriver driver;
        final CoreChecks.Clock clock = new CoreChecks.Clock();
        final List<byte[]> frames = new ArrayList<>();
        final List<Runnable> completions = new ArrayList<>();
        final EnumMap<FeatureId, FeatureDescriptor> specs = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, Integer> values = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, String> texts = new EnumMap<>(FeatureId.class);
        int connections, errors, failures, mode = -1;
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

        void open() { driver.onOpen(); }
        void receive(String value) { driver.received(hex(value)); }
        void receive(byte[] value) { driver.received(value); }

        void flushLast() {
            int i = completions.size() - 1;
            Runnable r = completions.set(i, null);
            if (r != null) r.run();
        }

        void flushAll() {
            for (int i = 0; i < completions.size(); i++) {
                Runnable r = completions.set(i, null);
                if (r != null) r.run();
            }
        }

        void last(String expected) {
            last(hex(expected));
        }

        void last(byte[] expected) {
            check(!frames.isEmpty() && Arrays.equals(frames.get(frames.size() - 1), expected),
                    driver.brandName() + " frame mismatch: got " + (frames.isEmpty() ? "(none)" : toHex(frames.get(frames.size() - 1)))
                            + ", expected " + toHex(expected));
        }

        @Override public void send(byte[] bytes, Runnable flushed) {
            frames.add(bytes.clone());
            completions.add(flushed);
        }
        @Override public void after(Runnable work, long delay) { clock.after(work, delay); }
        @Override public void cancel(Runnable work) { clock.cancel(work); }
        @Override public void useBle(UUID s, UUID n, UUID w, long d, long t) { }
        @Override public void fail(String message) { failures++; }
        @Override public long millis() { return clock.now; }
        @Override public void trace(String tag, String message) { }
        @Override public void onConnected(String name) { connections++; }
        @Override public void onDisconnected(String reason) { }
        @Override public void onError(String msg) { errors++; }
        @Override public void onBattery(int l, int r, int b, int f) { }
        @Override public void onNoiseMode(int value) { mode = value; }
        @Override public void onSpatial(Boolean state) { }
        @Override public void onDeepX(Integer m) { eq = m; }
        @Override public void onEqualizer(Integer m) { eq = m; }
        @Override public void onFeatureSpec(FeatureDescriptor spec) { specs.put(spec.id, spec); }
        @Override public void onFeatureValue(FeatureId id, int value) { values.put(id, value); }
        @Override public void onFeatureText(FeatureId id, String text) { texts.put(id, text); }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    private static byte[] edifierResp(int cmd, byte[] plain, boolean xor) {
        byte[] out = new byte[plain.length + 6];
        out[0] = (byte) 0xBB;
        out[1] = (byte) 0xEC;
        out[2] = (byte) cmd;
        out[3] = (byte) (plain.length >> 8);
        out[4] = (byte) plain.length;
        for (int i = 0; i < plain.length; i++) out[5 + i] = (byte) (plain[i] ^ (xor ? 0xA5 : 0));
        int sum = 0;
        for (int i = 0; i < out.length - 1; i++) sum += out[i] & 0xFF;
        out[out.length - 1] = (byte) sum;
        return out;
    }

    private static byte[] roseV2Frame(int seq, byte[] tlvs) {
        byte[] out = new byte[2 + tlvs.length + 2];
        out[0] = (byte) 0xDD;
        out[1] = (byte) seq;
        System.arraycopy(tlvs, 0, out, 2, tlvs.length);
        int sum = 0;
        for (int i = 0; i < out.length - 2; i++) sum += out[i] & 0xFF;
        out[out.length - 2] = (byte) (sum & 0xFF);
        out[out.length - 1] = (byte) 0xAA;
        return out;
    }

    private static void niceHckChecks() {
        Rig r = new Rig("NiceHCK OriG in");
        r.open();
        r.clock.tick(120);
        check(r.driver.verifLevel() == VERIF_REAL, "NiceHCK OriG in keeps VERIF_REAL");

        // Wind 0x11 reports BOTH MODE_ANC and ANC_LEVEL=0x11 (ROLE_WIND).
        r.receive("4E 04 00 00 01 01 11");
        FeatureDescriptor anc = r.specs.get(FeatureId.ANC_LEVEL);
        check(r.mode == MODE_ANC && anc != null && anc.allowsNoiseMode(MODE_ANC)
                && !anc.allowsNoiseMode(MODE_TRANS)
                && anc.findOption(0x11) != null && anc.findOption(0x11).role == OptionItem.ROLE_WIND
                && Integer.valueOf(0x11).equals(r.values.get(FeatureId.ANC_LEVEL)),
                "NiceHCK 0x11 wind reports MODE_ANC + ANC_LEVEL=0x11 (ROLE_WIND)");

        int[] flushed = {0};
        r.driver.writeFeature(FeatureId.ANC_LEVEL, 3, () -> flushed[0]++);
        r.last("4E 05 00 00 01 02 03 00");
        r.flushLast();
        check(flushed[0] == 1, "NiceHCK ANC_LEVEL write invokes flush");
        r.clock.tick(150);
        check(Arrays.equals(r.frames.get(r.frames.size() - 2), hex("4E 03 00 00 01 01")),
                "NiceHCK ANC_LEVEL schedules 150ms readback");

        r.driver.writeFeature(FeatureId.ANC_LEVEL, 0x11, () -> { });
        r.last("4E 05 00 00 01 02 11 00");
        r.flushLast();

        // Game Mode (LOW_LATENCY): 0x0108 / 0x0208.
        int beforeGame = r.frames.size();
        r.driver.writeFeature(FeatureId.LOW_LATENCY, 1, () -> { });
        check(r.frames.size() == beforeGame, "NiceHCK blocks LOW_LATENCY write before 0x0108 confirmation");
        r.receive("4E 04 00 00 08 01 00");
        check(r.specs.containsKey(FeatureId.LOW_LATENCY)
                && Integer.valueOf(0).equals(r.values.get(FeatureId.LOW_LATENCY)),
                "NiceHCK 0x0108 confirms LOW_LATENCY=0");
        r.driver.writeFeature(FeatureId.LOW_LATENCY, 1, () -> flushed[0]++);
        r.last("4E 04 00 00 08 02 01");
        r.flushLast();
        check(flushed[0] == 2, "NiceHCK LOW_LATENCY flush invoked");
        r.clock.tick(150);
        r.last("4E 03 00 00 08 01");

        // FW < 4.08 gates EQ presets 5 & 6.
        r.receive("4E 05 00 00 03 00 07 04"); // sub=7, main=4 -> 4.07
        check("4.07".equals(r.texts.get(FeatureId.FIRMWARE_INFO)), "NiceHCK 0x0003 decodes 4.07");
        r.receive("4E 04 00 00 07 01 02");
        FeatureDescriptor eq5 = r.specs.get(FeatureId.EQUALIZER);
        check(eq5 != null && eq5.options.length == 5 && eq5.isValidValue(4)
                && !eq5.isValidValue(5) && !eq5.isValidValue(6)
                && Integer.valueOf(2).equals(r.eq),
                "NiceHCK FW 4.07 gates EQ to 5 presets (0..4)");
        int beforeBlocked = r.frames.size();
        r.driver.writeFeature(FeatureId.EQUALIZER, 5, () -> { });
        r.driver.writeFeature(FeatureId.EQUALIZER, 6, () -> { });
        check(r.frames.size() == beforeBlocked, "NiceHCK FW 4.07 blocks EQ preset 5/6 writes");

        // FW >= 4.08 unlocks 7-preset EQ (0..6).
        r.receive("4E 05 00 00 03 00 08 04"); // sub=8, main=4 -> 4.08
        check("4.08".equals(r.texts.get(FeatureId.FIRMWARE_INFO)), "NiceHCK 0x0003 decodes 4.08");
        FeatureDescriptor eq7 = r.specs.get(FeatureId.EQUALIZER);
        check(eq7 != null && eq7.options.length == 7 && eq7.isValidValue(5) && eq7.isValidValue(6),
                "NiceHCK FW 4.08 upgrades EQ to 7 presets (0..6)");
        r.driver.writeFeature(FeatureId.EQUALIZER, 5, () -> flushed[0]++);
        r.last("4E 04 00 00 07 02 05");
        r.flushLast();
        check(flushed[0] == 3, "NiceHCK EQ write flush invoked");
        r.clock.tick(150);
        r.last("4E 03 00 00 07 01");
        r.driver.writeFeature(FeatureId.EQUALIZER, 6, () -> { });
        r.last("4E 04 00 00 07 02 06");

        // Pending EQ 5 before FW arrives stays hidden until 0x0003 confirms >= 4.08.
        Rig r2 = new Rig("NiceHCK OriG in");
        r2.open();
        r2.receive("4E 04 00 00 07 01 05");
        check(!r2.specs.containsKey(FeatureId.EQUALIZER), "NiceHCK holds preset 5 until FW >= 4.08 confirmed");
        r2.receive("4E 05 00 00 03 00 00 05"); // 5.00
        check("5.00".equals(r2.texts.get(FeatureId.FIRMWARE_INFO))
                && Integer.valueOf(5).equals(r2.values.get(FeatureId.EQUALIZER))
                && r2.specs.get(FeatureId.EQUALIZER).options.length == 7,
                "NiceHCK FW 5.00 releases pending preset 5 with 7-preset descriptor");

        // Never transmit Codec 0x0204.
        for (byte[] f : r.frames) {
            int op = (f[4] & 0xFF) | ((f[5] & 0xFF) << 8);
            check(op != 0x0204 && op != 0x0004, "NiceHCK never sends codec reboot opcodes");
        }
    }

    private static void edifierChecks() {
        // FitBuds Turbo (plaintext V2) with group 0x10 (Deep/Comfort/Wind) + Game Mode.
        Rig turbo = new Rig("EDIFIER FitBuds Turbo");
        turbo.open();
        turbo.receive(edifierResp(0xCC, hex("10 03"), false)); // group 0x10 Wind
        FeatureDescriptor anc10 = turbo.specs.get(FeatureId.ANC_LEVEL);
        check(turbo.mode == MODE_ANC && anc10 != null
                && anc10.findOption(3) != null && anc10.findOption(3).role == OptionItem.ROLE_WIND
                && Integer.valueOf(3).equals(turbo.values.get(FeatureId.ANC_LEVEL)),
                "Edifier group 0x10 wind reports MODE_ANC + ANC_LEVEL=3 (ROLE_WIND)");

        // No 0x08/0x09 emitted during initial probe/receive/changeNoise.
        turbo.driver.setNoiseMode(MODE_ANC);
        for (byte[] f : turbo.frames) {
            int cmd = f[2] & 0xFF;
            check(cmd == 0xF2 || cmd == 0xD0 || cmd == 0xCC || cmd == 0xC1,
                    "Edifier initial probe + changeNoise never emits 0x08/0x09");
        }

        // Write Comfort (2) and Wind (3) on group 0x10.
        int[] flushed = {0};
        turbo.driver.writeFeature(FeatureId.ANC_LEVEL, 2, () -> flushed[0]++);
        byte[] last = turbo.frames.get(turbo.frames.size() - 1);
        check((last[2] & 0xFF) == 0xC1 && last[5] == 0x10 && last[6] == 2, "Edifier writes 0x10 Comfort ANC");
        turbo.flushLast();
        check(flushed[0] == 1, "Edifier ANC_LEVEL flush invoked");

        // Explicit Game Mode query/write (0x08 / 0x09).
        int beforeGameWrite = turbo.frames.size();
        turbo.driver.writeFeature(FeatureId.LOW_LATENCY, 1, () -> { });
        check(turbo.frames.size() == beforeGameWrite, "Edifier blocks 0x09 write before 0x08 confirmation");

        turbo.driver.queryFeature(FeatureId.LOW_LATENCY);
        check((turbo.frames.get(turbo.frames.size() - 1)[2] & 0xFF) == 0x08, "Edifier queryFeature sends 0x08");
        turbo.receive(edifierResp(0x08, hex("00"), false));
        FeatureDescriptor gameSpec = turbo.specs.get(FeatureId.LOW_LATENCY);
        check(gameSpec != null && gameSpec.verif == VERIF_UNTESTED
                && Integer.valueOf(0).equals(turbo.values.get(FeatureId.LOW_LATENCY)),
                "Edifier 0x08 confirms LOW_LATENCY=0");

        turbo.driver.writeFeature(FeatureId.LOW_LATENCY, 1, () -> flushed[0]++);
        byte[] gameWrite = turbo.frames.get(turbo.frames.size() - 1);
        check((gameWrite[2] & 0xFF) == 0x09 && gameWrite[5] == 1, "Edifier writeFeature sends 0x09 [01]");
        turbo.flushLast();
        check(flushed[0] == 2, "Edifier LOW_LATENCY flush invoked");
        turbo.clock.tick(350);
        check((turbo.frames.get(turbo.frames.size() - 1)[2] & 0xFF) == 0x08, "Edifier LOW_LATENCY readback 0x08");

        // Group 0x1B (XOR V2) Wind (4).
        Rig evo = new Rig("EDIFIER NeoBuds Pro 2");
        evo.open();
        evo.receive(edifierResp(0xCC, hex("1B 04"), true));
        FeatureDescriptor anc1B = evo.specs.get(FeatureId.ANC_LEVEL);
        check(evo.mode == MODE_ANC && anc1B != null && anc1B.options.length == 4
                && anc1B.findOption(4).role == OptionItem.ROLE_WIND
                && Integer.valueOf(4).equals(evo.values.get(FeatureId.ANC_LEVEL)),
                "Edifier group 0x1B wind reports MODE_ANC + ANC_LEVEL=4 (ROLE_WIND)");
        evo.driver.writeFeature(FeatureId.ANC_LEVEL, 4, () -> { });
        byte[] evoWrite = evo.frames.get(evo.frames.size() - 1);
        check((evoWrite[2] & 0xFF) == 0xC1 && (evoWrite[5] ^ (byte) 0xA5) == 0x1B
                && (evoWrite[6] ^ (byte) 0xA5) == 4, "Edifier XOR V2 writes 0x1B Wind");

        // Never emit LDAC 0x49.
        for (byte[] f : turbo.frames) check((f[2] & 0xFF) != 0x49, "Edifier never sends LDAC 0x49");
        for (byte[] f : evo.frames) check((f[2] & 0xFF) != 0x49, "Edifier never sends LDAC 0x49");
    }

    private static List<byte[]> sonyPayloads(List<byte[]> frames) {
        SonyProtocol.Decoder dec = new SonyProtocol.Decoder();
        List<byte[]> out = new ArrayList<>();
        for (byte[] f : frames) {
            for (SonyProtocol.Frame decoded : dec.feed(f)) {
                check(decoded.type != 14, "Sony never sends COMMAND_2 (0x0E) unpair frames");
                if (decoded.type == 12) out.add(decoded.payload);
            }
        }
        return out;
    }

    private static boolean containsPayload(List<byte[]> payloads, String expectedHex) {
        byte[] target = hex(expectedHex);
        for (byte[] p : payloads) if (Arrays.equals(p, target)) return true;
        return false;
    }

    private static void sonyAck(Rig r, int nextSeq) {
        r.receive(SonyProtocol.encode(1, nextSeq, new byte[0]));
    }

    private static void sonyChecks() {
        // V2 WF-1000XM4/XM5 full flow: hello -> 06 00 discovery -> 0x15 Wind, Trans, DSEE, EQ, Speak-to-Chat (INVERTED!), Wear (INVERTED!), Codec.
        Rig v2 = new Rig("WF-1000XM5");
        v2.open();
        check(v2.driver.verifLevel() == VERIF_UNTESTED, "Sony keeps VERIF_UNTESTED");
        check(v2.frames.size() == 1, "Sony sends only 00 00 before version hello");

        // Ack hello (seq 0 -> 1) and deliver V2 version reply.
        v2.flushLast();
        sonyAck(v2, 1);
        int seqIn = 0;
        v2.receive(SonyProtocol.encode(12, seqIn, hex("01 00 03 00 10 02 00 00")));
        seqIn = 1 - seqIn;
        v2.clock.tick(0);

        // Drain queued commands by ACKing each one.
        int txSeq = 1;
        for (int step = 0; step < 12; step++) {
            v2.flushAll();
            txSeq = 1 - txSeq;
            sonyAck(v2, txSeq);
        }
        List<byte[]> sent = sonyPayloads(v2.frames);
        check(containsPayload(sent, "06 00"), "Sony queries 06 00 capabilities after ready()");
        check(containsPayload(sent, "12 02"), "Sony queries 12 02 codec after ready()");

        // Deliver 07 00 capability list: ASM15(0x68), EQ(0x51), DSEE(0xE2), PauseOnRemove(0xF1), SpeakToChat(0xF2).
        v2.receive(SonyProtocol.encode(12, seqIn, hex("07 00 05 68 01 51 01 E2 01 F1 01 F2 01")));
        seqIn = 1 - seqIn;
        for (int step = 0; step < 6; step++) {
            v2.flushAll();
            txSeq = 1 - txSeq;
            sonyAck(v2, txSeq);
        }
        sent = sonyPayloads(v2.frames);
        check(containsPayload(sent, "E6 01"), "Sony capability 0xE2 triggers V2 DSEE query E6 01");
        check(containsPayload(sent, "56 00"), "Sony capability 0x51 triggers V2 EQ query 56 00");
        check(containsPayload(sent, "F6 0C"), "Sony capability 0xF2 triggers V2 Speak-to-Chat query F6 0C");
        check(containsPayload(sent, "F6 01"), "Sony capability 0xF1 triggers V2 Pause-on-Remove query F6 01");

        // V2 0x15 Wind mode (0x03) + ambient level 10.
        v2.receive(SonyProtocol.encode(12, seqIn, hex("67 15 01 01 00 03 00 0A")));
        seqIn = 1 - seqIn;
        check(v2.mode == MODE_ANC
                && Integer.valueOf(3).equals(v2.values.get(FeatureId.ANC_LEVEL))
                && v2.specs.get(FeatureId.ANC_LEVEL).findOption(3).role == OptionItem.ROLE_WIND
                && Integer.valueOf(1).equals(v2.values.get(FeatureId.WIND_REDUCTION))
                && Integer.valueOf(10).equals(v2.values.get(FeatureId.TRANS_MODE)),
                "Sony V2 0x15 wind decodes MODE_ANC, ANC_LEVEL=3 (ROLE_WIND), WIND_REDUCTION=1, TRANS_MODE=10");

        // Write TRANS_MODE Focus on Voice (120) and level 15.
        v2.driver.writeFeature(FeatureId.TRANS_MODE, SonyProtocol.TRANS_CHOICE_VOICE, () -> { });
        v2.clock.tick(0);
        v2.flushAll(); txSeq = 1 - txSeq; sonyAck(v2, txSeq);
        check(containsPayload(sonyPayloads(v2.frames), "68 15 01 01 01 03 01 0A"),
                "Sony V2 Focus on Voice sets asm=1, voice=1, preserving level 10");

        v2.driver.writeFeature(FeatureId.TRANS_MODE, 15, () -> { });
        v2.clock.tick(0);
        v2.flushAll(); txSeq = 1 - txSeq; sonyAck(v2, txSeq);
        check(containsPayload(sonyPayloads(v2.frames), "68 15 01 01 01 03 00 0F"),
                "Sony V2 TRANS_MODE=15 writes voice=0, level=15");

        // Write WIND_REDUCTION=0 (normal NC) and ANC_LEVEL=3 (wind).
        v2.driver.writeFeature(FeatureId.WIND_REDUCTION, 0, () -> { });
        v2.clock.tick(0);
        v2.flushAll(); txSeq = 1 - txSeq; sonyAck(v2, txSeq);
        check(containsPayload(sonyPayloads(v2.frames), "68 15 01 01 00 02 00 0A"),
                "Sony V2 WIND_REDUCTION=0 writes nc=0x02");

        // EQ preset 57 00 16 (Bass+) -> write 58 00 14 00 (Vocal).
        v2.receive(SonyProtocol.encode(12, seqIn, hex("57 00 16 00")));
        seqIn = 1 - seqIn;
        check(Integer.valueOf(0x16).equals(v2.eq)
                && Integer.valueOf(0x16).equals(v2.values.get(FeatureId.EQUALIZER))
                && (v2.driver.caps() & CAP_EQUALIZER) != 0,
                "Sony V2 57 00 16 confirms EQUALIZER=0x16");
        v2.driver.writeFeature(FeatureId.EQUALIZER, 0x14, () -> { });
        v2.clock.tick(0);
        v2.flushAll(); txSeq = 1 - txSeq; sonyAck(v2, txSeq);
        check(containsPayload(sonyPayloads(v2.frames), "58 00 14 00"), "Sony V2 EQ writes 58 00 14 00");

        // DSEE V2: E7 01 01 -> ON, setDsee(false) -> E8 01 00.
        SonyClient sonyV2 = (SonyClient) v2.driver;
        v2.receive(SonyProtocol.encode(12, seqIn, hex("E7 01 01")));
        seqIn = 1 - seqIn;
        check(Boolean.TRUE.equals(sonyV2.dseeEnabled()), "Sony V2 E7 01 01 reports DSEE ON");
        sonyV2.setDsee(false, () -> { });
        v2.clock.tick(0);
        v2.flushAll(); txSeq = 1 - txSeq; sonyAck(v2, txSeq);
        check(containsPayload(sonyPayloads(v2.frames), "E8 01 00"), "Sony V2 setDsee(false) writes E8 01 00");

        // Speak-to-Chat V2 INVERTED: 0x00 = ON, 0x01 = OFF!
        v2.receive(SonyProtocol.encode(12, seqIn, hex("F7 0C 00 01")));
        seqIn = 1 - seqIn;
        check(Boolean.TRUE.equals(sonyV2.speakToChatEnabled()), "Sony V2 F7 0C 00 is INVERTED -> ON");
        sonyV2.setSpeakToChat(false, () -> { });
        v2.clock.tick(0);
        v2.flushAll(); txSeq = 1 - txSeq; sonyAck(v2, txSeq);
        check(containsPayload(sonyPayloads(v2.frames), "F8 0C 01 01"), "Sony V2 setSpeakToChat(false) writes INVERTED F8 0C 01 01");
        sonyV2.setSpeakToChat(true, () -> { });
        v2.clock.tick(0);
        v2.flushAll(); txSeq = 1 - txSeq; sonyAck(v2, txSeq);
        check(containsPayload(sonyPayloads(v2.frames), "F8 0C 00 01"), "Sony V2 setSpeakToChat(true) writes INVERTED F8 0C 00 01");

        // Pause-on-Remove / WEAR_DETECTION V2 INVERTED: 0x00 = ON, 0x01 = OFF!
        v2.receive(SonyProtocol.encode(12, seqIn, hex("F7 01 00")));
        seqIn = 1 - seqIn;
        check(Integer.valueOf(1).equals(v2.values.get(FeatureId.WEAR_DETECTION)), "Sony V2 F7 01 00 is INVERTED -> WEAR_DETECTION=1");
        v2.driver.writeFeature(FeatureId.WEAR_DETECTION, 0, () -> { });
        v2.clock.tick(0);
        v2.flushAll(); txSeq = 1 - txSeq; sonyAck(v2, txSeq);
        check(containsPayload(sonyPayloads(v2.frames), "F8 01 01"), "Sony V2 WEAR_DETECTION=0 writes INVERTED F8 01 01");

        // Read-only Codec 13 02 10 -> LDAC in FIRMWARE_INFO text.
        v2.receive(SonyProtocol.encode(12, seqIn, hex("13 02 10")));
        String info = v2.texts.get(FeatureId.FIRMWARE_INFO);
        check(info != null && info.contains("LDAC"), "Sony V2 13 02 10 reports LDAC in FIRMWARE_INFO text");

        // V1 (WH-1000XM4) normal polarity for Speak-to-Chat (F8 05 01 <1|0>), V1 Wind (0x02), V1 DSEE (E8 02 00 <0|1>), V1 Codec (19 00).
        check(Boolean.TRUE.equals(SonyProtocol.speakToChat(1, hex("F7 05 01 01")))
                && Boolean.FALSE.equals(SonyProtocol.speakToChat(1, hex("F7 05 01 00"))),
                "Sony V1 Speak-to-Chat uses normal polarity");
        check(Arrays.equals(hex("F8 05 01 01"), SonyProtocol.speakToChatCommand(1, true))
                && Arrays.equals(hex("F8 05 01 00"), SonyProtocol.speakToChatCommand(1, false)),
                "Sony V1 Speak-to-Chat command polarity");
        check(Arrays.equals(hex("E8 02 00 01"), SonyProtocol.dseeCommand(1, true))
                && Boolean.TRUE.equals(SonyProtocol.dsee(1, hex("E7 02 00 01"))),
                "Sony V1 DSEE encode/decode");
        check(SonyProtocol.isWindActive(1, hex("67 02 01 02 01 01 00 00"))
                && Arrays.equals(hex("68 02 01 02 02 01 00 00"),
                SonyProtocol.windCommand(1, hex("67 02 01 02 01 01 00 00"), false)),
                "Sony V1 0x02 wind decode/write");
        check("AAC".equals(SonyProtocol.codec(1, hex("19 00 02"))), "Sony V1 codec AAC");
    }

    private static void technicsChecks() {
        Rig az = new Rig("Technics EAH-AZ80");
        az.open();
        az.clock.tick(0);
        az.flushAll();
        az.clock.tick(120); az.flushAll();
        az.clock.tick(120); az.flushAll();

        // Device reports ANC with ancLevel=75 (0x4B), ambientLevel=25 (0x19).
        az.receive("05 5B 06 00 0A 00 00 01 4B 19");
        FeatureDescriptor ancSpec = az.specs.get(FeatureId.ANC_LEVEL);
        FeatureDescriptor transSpec = az.specs.get(FeatureId.TRANS_MODE);
        check(ancSpec != null && ancSpec.verif == VERIF_UNTESTED
                && Integer.valueOf(75).equals(az.values.get(FeatureId.ANC_LEVEL)),
                "Technics 0x000A confirms ANC_LEVEL=75");
        check(transSpec != null && transSpec.verif == VERIF_UNTESTED
                && Integer.valueOf(25).equals(az.values.get(FeatureId.TRANS_MODE)),
                "Technics 0x000A confirms TRANS_MODE=25");

        // Set ANC_LEVEL=50 -> preserves ambientLevel=25 in 0x000B!
        int start = az.frames.size();
        int[] flushed = {0};
        az.driver.writeFeature(FeatureId.ANC_LEVEL, 50, () -> flushed[0]++);
        az.clock.tick(0);
        az.flushLast(); // 0x0068 (0)
        az.clock.tick(120);
        az.flushLast(); // 0x000B (1, 50, 25)
        az.clock.tick(120);
        az.flushLast(); // 0x0039
        check(flushed[0] == 1, "Technics ANC_LEVEL write invokes flush");
        byte[] setAnc = az.frames.get(start + 1);
        check((setAnc[4] & 0xFF) == 0x0B && setAnc[6] == 1 && (setAnc[7] & 0xFF) == 50 && (setAnc[8] & 0xFF) == 25,
                "Technics ANC_LEVEL=50 writes 0x000B preserving ambient=25");

        // Set TRANS_MODE=100 -> preserves ancLevel=50 in 0x000B!
        int tStart = az.frames.size();
        az.driver.writeFeature(FeatureId.TRANS_MODE, 100, () -> flushed[0]++);
        az.clock.tick(0);
        az.flushLast(); // 0x0068 (0)
        az.clock.tick(120);
        az.flushLast(); // 0x0022
        az.clock.tick(120);
        az.flushLast(); // 0x000B (2, 50, 100)
        check(flushed[0] == 2, "Technics TRANS_MODE write invokes flush");
        byte[] setTrans = az.frames.get(tStart + 2);
        check((setTrans[4] & 0xFF) == 0x0B && setTrans[6] == 2 && (setTrans[7] & 0xFF) == 50 && (setTrans[8] & 0xFF) == 100,
                "Technics TRANS_MODE=100 writes 0x000B preserving anc=50");

        // Adaptive ANC: queryFeature(ANC_LEVEL) emits 0x0067; reply [00, 01] adds Adaptive option (ROLE_ADAPTIVE).
        az.driver.queryFeature(FeatureId.ANC_LEVEL);
        az.clock.tick(0);
        az.last("05 5A 02 00 67 00");
        az.receive("05 5B 04 00 67 00 00 01");
        FeatureDescriptor adaptiveSpec = az.specs.get(FeatureId.ANC_LEVEL);
        check(adaptiveSpec != null && adaptiveSpec.findOption(TechnicsClient.ADAPTIVE_OPTION_ID) != null
                && adaptiveSpec.findOption(TechnicsClient.ADAPTIVE_OPTION_ID).role == OptionItem.ROLE_ADAPTIVE
                && Integer.valueOf(TechnicsClient.ADAPTIVE_OPTION_ID).equals(az.values.get(FeatureId.ANC_LEVEL)),
                "Technics 0x0067=1 confirms Adaptive ANC option (ROLE_ADAPTIVE)");

        // Enable Adaptive ANC writes 0x000B (1, 50, 100) -> 0x0068 (1).
        int adStart = az.frames.size();
        az.driver.writeFeature(FeatureId.ANC_LEVEL, TechnicsClient.ADAPTIVE_OPTION_ID, () -> flushed[0]++);
        az.clock.tick(0);
        az.flushLast();
        az.clock.tick(120);
        az.flushLast();
        check(flushed[0] == 3, "Technics Adaptive ANC flush invoked");
        check(Arrays.equals(az.frames.get(adStart), hex("05 5A 05 00 0B 00 01 32 64"))
                && Arrays.equals(az.frames.get(adStart + 1), hex("05 5A 03 00 68 00 01")),
                "Technics Adaptive enable writes 0x000B(01,50,100) then 0x0068(01)");

        // Safety: ONLY whitelisted Airoha RACE IDs are ever emitted!
        for (byte[] f : az.frames) {
            int id = (f[4] & 0xFF) | ((f[5] & 0xFF) << 8);
            check(id == 0x000A || id == 0x000B || id == 0x0022 || id == 0x0039
                    || id == 0x0067 || id == 0x0068 || id == 0x0CD6 || id == 0x0040,
                    "Technics never probes unknown RACE ID 0x" + Integer.toHexString(id));
        }
    }

    private static void roseLinkChecks() {
        Rig r = new Rig("CERAMICS-U");
        r.open();
        check(r.frames.size() == 1, "RoseLink opened() sends only 1 frame");

        // Single V2 status packet with Battery(0C), Wind(09 04), Depth(2C 05), Game(0E 01), EQ(2A 02), In-Ear(08 00=ON inverted!).
        byte[] tlvs = concat(
                hex("04 0C E4 63 FF"),
                hex("02 09 04"),
                hex("02 2C 05"),
                hex("02 0E 01"),
                hex("02 2A 02"),
                hex("02 08 00")
        );
        r.receive(roseV2Frame(0, tlvs));
        FeatureDescriptor anc = r.specs.get(FeatureId.ANC_LEVEL);
        check(r.connections == 1 && r.mode == MODE_ANC
                && anc != null && anc.verif == VERIF_UNTESTED
                && anc.findOption(4) != null && anc.findOption(4).role == OptionItem.ROLE_WIND
                && Integer.valueOf(4).equals(r.values.get(FeatureId.ANC_LEVEL)),
                "RoseLink V2 09 04 reports MODE_ANC + ANC_LEVEL=4 (ROLE_WIND)");
        check(Integer.valueOf(1).equals(r.values.get(FeatureId.LOW_LATENCY)),
                "RoseLink V2 0E 01 confirms LOW_LATENCY=1");
        check(Integer.valueOf(2).equals(r.values.get(FeatureId.EQUALIZER))
                && Integer.valueOf(2).equals(r.eq) && (r.driver.caps() & CAP_EQUALIZER) != 0,
                "RoseLink V2 2A 02 confirms EQUALIZER=2 (Rock)");
        check(Integer.valueOf(1).equals(r.values.get(FeatureId.WEAR_DETECTION)),
                "RoseLink V2 08 00 is INVERTED -> WEAR_DETECTION=1 (ON)");

        // Switch from Wind (4) to Deep (5) sends 09 01 (ANC) + 2C 05 (Deep).
        int beforeDepth = r.frames.size();
        int[] flushed = {0};
        r.driver.writeFeature(FeatureId.ANC_LEVEL, 5, () -> flushed[0]++);
        check(r.frames.size() == beforeDepth + 2, "RoseLink leaving Wind to Deep sends 09 01 then 2C 05");
        byte[] fAnc = r.frames.get(beforeDepth);
        byte[] fDepth = r.frames.get(beforeDepth + 1);
        check(fAnc[3] == 0x09 && fAnc[4] == 0x01 && fDepth[3] == 0x2C && fDepth[4] == 0x05,
                "RoseLink writes 09 01 + 2C 05");
        r.flushLast();
        check(flushed[0] == 1, "RoseLink ANC_LEVEL flush invoked");

        // Switch to Wind (4) sends 09 04.
        r.driver.writeFeature(FeatureId.ANC_LEVEL, 4, () -> { });
        byte[] fWind = r.frames.get(r.frames.size() - 1);
        check(fWind[3] == 0x09 && fWind[4] == 0x04, "RoseLink ANC_LEVEL=4 writes 09 04");

        // Write Game Mode OFF (0E 00) and EQ Pop (2A 01).
        r.driver.writeFeature(FeatureId.LOW_LATENCY, 0, () -> { });
        byte[] fGame = r.frames.get(r.frames.size() - 1);
        check(fGame[3] == 0x0E && fGame[4] == 0x00, "RoseLink LOW_LATENCY=0 writes 0E 00");

        r.driver.writeFeature(FeatureId.EQUALIZER, 1, () -> { });
        byte[] fEq = r.frames.get(r.frames.size() - 1);
        check(fEq[3] == 0x2A && fEq[4] == 0x01, "RoseLink EQUALIZER=1 writes 2A 01");

        // Write In-Ear OFF (0) -> INVERTED wire byte 08 01! Write ON (1) -> 08 00!
        r.driver.writeFeature(FeatureId.WEAR_DETECTION, 0, () -> { });
        byte[] fWearOff = r.frames.get(r.frames.size() - 1);
        check(fWearOff[3] == 0x08 && fWearOff[4] == 0x01, "RoseLink WEAR_DETECTION=0 writes INVERTED 08 01");
        r.driver.writeFeature(FeatureId.WEAR_DETECTION, 1, () -> { });
        byte[] fWearOn = r.frames.get(r.frames.size() - 1);
        check(fWearOn[3] == 0x08 && fWearOn[4] == 0x00, "RoseLink WEAR_DETECTION=1 writes INVERTED 08 00");

        // Safety: NEVER write Multipoint (0x32) or Codec (0x2B).
        for (int i = 1; i < r.frames.size(); i++) {
            int item = r.frames.get(i)[3] & 0xFF;
            check(item != 0x32 && item != 0x2B, "RoseLink never writes reboot items 0x32/0x2B");
        }
    }

    public static void main(String[] args) {
        niceHckChecks();
        edifierChecks();
        sonyChecks();
        technicsChecks();
        roseLinkChecks();
        System.out.println("PASS " + checks + " other-brands extension checks");
    }
}

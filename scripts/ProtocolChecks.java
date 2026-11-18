package com.hdz.wearcontrol;

import com.hdz.wearcontrol.xiaomi.XiaomiAuth;
import com.hdz.wearcontrol.xiaomi.XiaomiProtocol;
import com.hdz.wearcontrol.oppo.OppoProtocol;
import com.hdz.wearcontrol.vivo.VivoProtocol;
import com.hdz.wearcontrol.huawei.HuaweiSoundProtocol;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 协议回归：公开报文是真值；不连接蓝牙，不向设备发指令。 */
public final class ProtocolChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        edifier();
        xiaomi();
        rose();
        niceHck();
        oppo();
        sony();
        technics();
        vivo();
        scrollFeedback();
        indicatorMotion();
        snapshots();
        liveState();
        volumeGuard();
        soundEffects();
        tileActions();
        auditRegressions();
        writer();
        System.out.println("PASS: " + checks + " protocol/state checks");
    }

    private static void tileActions() {
        TileSnapshot resource = TileSnapshot.forResources(50, 60, 70, -1, 0, TwsClient.MODE_OFF,
                TwsClient.CAP_BATTERY | TwsClient.CAP_NOISE, TwsClient.MODE_ANC, 1L, BluetoothLink.CONNECTED);
        check(resource.pendingMode == TwsClient.MODE_ANC, "late resource request must restore issued pending image");
        check(resource.withoutPending().mode == TwsClient.MODE_OFF, "timeline fallback has a distinct confirmed image");
        check(TileSnapshot.forResources(50, 60, 70, -1, 0, TwsClient.MODE_OFF,
                TwsClient.CAP_BATTERY, TwsClient.MODE_ANC, 1L, BluetoothLink.DISCONNECTED).battL == -1,
                "resource restoration cannot light offline battery or pending state");
        String address = "AA:BB:CC:DD:EE:FF";
        String id = TileCommand.id(4, TwsClient.MODE_ANC, address);
        check(TileCommand.parse(id, address, 4, 3, 15) != null, "fresh tile click is accepted");
        check(TileCommand.parse(id, address, 5, 4, 2) == null, "replayed host state cannot resend command");
        check(TileCommand.parse(TileCommand.id(4, TwsClient.MODE_OFF, address), address, 4, 4, 2) != null,
                "another target on same rendered tile remains usable");
        check(TileCommand.parse(id, address, 5, 5, 0) == null, "out-of-order old render cannot override new target");
        check(TileCommand.parse(id, "00:00:00:00:00:00", 5, 0, 0) == null, "cached tile cannot control another device");
        check(TileCommand.parse(id, address, 3, 0, 0) == null, "unissued click token is rejected");
        check(TileCommand.parse(TileCommand.id(4, -1, address), address, 4, 3, 0).mode == -1, "refresh is explicit, never cycles noise");
        for (String bad : new String[]{"", "refresh", "mode_0", "4/3/" + address, "-1/0/" + address, "x/0/" + address})
            check(TileCommand.parse(bad, address, 4, 0, 0) == null, "malformed tile action has no side effect");
        check((TileSnapshot.displayCaps(TwsClient.CAP_NOISE, TwsClient.CAP_BATTERY,
                TwsClient.CAP_BATTERY | TwsClient.CAP_NOISE) & TwsClient.CAP_NOISE) != 0,
                "reconnect does not collapse previously confirmed tile ANC row");
        check((TileSnapshot.displayCaps(TwsClient.CAP_NOISE, TwsClient.CAP_BATTERY_SINGLE,
                TwsClient.CAP_BATTERY_SINGLE) & TwsClient.CAP_NOISE) == 0,
                "unsupported fallback cannot inherit an active ANC capability");
    }

    private static void liveState() {
        DeviceState state = new DeviceState();
        state.begin("A", "Earbuds", TwsClient.CAP_BATTERY, TwsClient.CAP_BATTERY | TwsClient.CAP_NOISE);
        check(state.capability(TwsClient.CAP_NOISE) == DeviceState.UNKNOWN, "ANC waiting is not unsupported");
        state.connected = true;
        state.battery(80, 255, 255, -1);
        state.battery(255, 70, 20, 0);
        check(state.left == 80 && state.right == 70 && state.box == 20, "partial reports merge in current session");
        state.capabilities(TwsClient.CAP_NOISE | TwsClient.CAP_BATTERY, TwsClient.CAP_NOISE);
        check(state.capability(TwsClient.CAP_NOISE) == DeviceState.SUPPORTED, "valid response enables ANC");
        state.disconnect();
        state.battery(99, 99, 99, 7);
        check(state.left == -1 && state.charge == -1 && state.mode == -1, "disconnect cannot display old live values");
        check(state.capability(TwsClient.CAP_NOISE) == DeviceState.UNKNOWN, "disconnect retains waiting card");
        state.presets = SoundPreset.vivo();
        state.begin("B", "Speaker", TwsClient.CAP_BATTERY_SINGLE, TwsClient.CAP_BATTERY_SINGLE);
        check(state.presets.length == 0 && state.capability(TwsClient.CAP_NOISE) == DeviceState.UNSUPPORTED,
                "new device cannot inherit optional controls");
        state.connected = true;
        state.battery(55, 60, 70, 7);
        check(state.left == 55 && state.right == -1 && state.box == -1 && state.charge == -1,
                "single battery never creates extra earbuds");
        state.capabilities(TwsClient.CAP_BATTERY, TwsClient.CAP_BATTERY);
        check(state.left == -1, "topology change discards incompatible values");

        TileSnapshot history = new TileSnapshot("A", 1234, 90, 80, 50, -1, 3, TwsClient.MODE_OFF,
                TwsClient.CAP_BATTERY | TwsClient.CAP_NOISE, 0, TwsClient.MODE_ANC, System.currentTimeMillis() + 10000L);
        for (int connection : new int[]{BluetoothLink.UNKNOWN, BluetoothLink.DISCONNECTED}) {
            TileSnapshot display = history.forConnection(connection);
            check(!display.hasBattery() && display.mode == -1 && display.chargeFlags == -1 && display.pendingMode == -1,
                    "unconfirmed connection hides saved battery and mode");
            check(display.lastOk == history.lastOk && display.caps == history.caps, "presentation retains history metadata");
        }
        TileSnapshot online = history.forConnection(BluetoothLink.CONNECTED);
        check(online.battL == 90 && online.mode == TwsClient.MODE_ANC && online.confirmedMode == TwsClient.MODE_OFF,
                "online tile keeps pending target separate from confirmed mode");
        check(history.battL == 90 && history.confirmedMode == TwsClient.MODE_OFF, "masking never mutates stored history");
        check(online.withoutPending().mode == TwsClient.MODE_OFF && online.withoutPending().pendingExpiresAt() == 0L,
                "tile timeline fallback never confirms an abandoned target");
    }

    private static void volumeGuard() {
        VolumeGuard guard = new VolumeGuard();
        check(!guard.isUnlocked() && guard.limit(2, 10, 10) == 2, "volume starts locked");
        check(guard.limit(10, 2, 10) == 2 && !guard.isUnlocked(), "locked volume can always be lowered");
        guard.unlock();
        check(guard.limit(2, 6, 10) == 6 && guard.isUnlocked(), "unlocked low range is usable");
        check(guard.limit(6, 7, 10) == 7 && !guard.isUnlocked() && guard.needsHighConfirmation(),
                "reaching exactly 70 percent relocks");
        check(guard.limit(7, 10, 10) == 7, "continuing same drag cannot cross guard");
        guard.unlock();
        check(guard.limit(7, 10, 10) == 10, "second explicit unlock permits high range");
        guard.lock(); guard.unlock();
        check(guard.limit(9, 10, 10) == 9 && guard.needsHighConfirmation(), "already high volume never jumps or drops on first increase");
        guard.lock(); guard.unlock();
        check(guard.limit(9, 3, 10) == 3, "guard allows lowering an existing high volume");
        guard.lock(); guard.unlock();
        check(guard.limit(2, 15, 15) == 10, "system steps round down, never above 70 percent");
        guard.unlock(); guard.lock();
        check(guard.limit(10, 15, 15) == 10 && !guard.needsHighConfirmation(), "page reentry resets both unlocks");
        guard.unlock();
        check(guard.limit(0, 1, 1) == 0, "coarse system range cannot skip confirmation");
        guard.unlock();
        check(guard.limit(0, 1, 1) == 1, "coarse range is usable after second unlock, not permanently locked");
        guard.lock(); guard.unlock();
        guard.limit(0, 10, 10); guard.unlock();
        check(guard.limit(10, 3, 10) == 3 && guard.limit(3, 10, 10) == 7,
                "lowering below boundary rearms high-volume confirmation");
        check(guard.limit(7, 2, 10) == 2 && !guard.needsHighConfirmation(),
                "locked lowering cancels stale high-volume confirmation");
        guard.unlock();
        check(guard.limit(2, 10, 10) == 7, "unlock after lowering cannot bypass next threshold");
        check(guard.limit(0, 99, 0) == 0, "zero range is safe");
        for (int max = 2; max <= 100; max++) {
            guard.lock(); guard.unlock();
            check(guard.limit(0, max, max) <= max * 7 / 10 && !guard.isUnlocked(), "all volume ranges stop before threshold");
        }
    }

    /** 外部审计线索的真实实现回归：断言应有行为，不以“检测出问题”作为 PASS。 */
    private static void auditRegressions() {
        byte[] vivo = hex("FF 04 00 05 00 1B 82 07 00 50 4A 64 00");
        byte[] stream = concat(hex("FF 55 FF 04 FF"), vivo, vivo);
        for (int cut = 0; cut <= stream.length; cut++) {
            VivoProtocol.Decoder decoder = new VivoProtocol.Decoder();
            List<VivoProtocol.Frame> frames = new ArrayList<VivoProtocol.Frame>();
            frames.addAll(decoder.feed(Arrays.copyOfRange(stream, 0, cut), cut));
            frames.addAll(decoder.feed(null, 1));
            frames.addAll(decoder.feed(Arrays.copyOfRange(stream, cut, stream.length), stream.length - cut));
            check(frames.size() == 2 && frames.get(0).vendor == 0x1B && frames.get(0).command == 0x8207,
                    "vivo survives false headers, all split boundaries and coalesced frames");
            check(Arrays.equals(VivoProtocol.battery(frames.get(0).payload), new int[]{80, 74, 100, 0}),
                    "vivo preserves battery payload after resynchronization");
        }
        byte[] nice = hex("4E 06 00 00 05 00 50 4A 64");
        byte[] niceStream = concat(hex("4E 05 00 99"), nice, nice);
        check(new NiceHckProtocol().feed(niceStream, niceStream.length).size() == 2,
                "NiceHCK false header cannot swallow a following valid frame");
        byte[] oppo = hex("AA 0C 00 00 0C 81 22 05 00 00 01 01 10 00");
        byte[] oppoStream = concat(hex("AA 08 01 00"), oppo, oppo);
        check(new OppoProtocol.Decoder().feed(oppoStream, oppoStream.length).size() == 2,
                "OPPO invalid slice resynchronizes without losing following frames");
        byte[] rose = hex("DD 00 04 0C E4 63 FF 02 09 01 3F AA");
        byte[] roseStream = concat(hex("DD 00 0D 77"), rose, hex("00 AA"));
        check(new RoseProtocol.Decoder(RoseProtocol.V2).feed(roseStream, roseStream.length).size() == 2,
                "Rose checksum failure cannot discard embedded valid frame");
        byte[] sony = SonyProtocol.encode(12, 0, new byte[200]);
        check(new SonyProtocol.Decoder().feed(sony).get(0).payload.length == 200,
                "Sony high-bit length is unsigned and does not deadlock");
        check(XiaomiProtocol.noise(hex("04 00 0B 01 00 02 00 0B")) == -1,
                "duplicate truncated Xiaomi noise TLV is rejected");
        check(OppoProtocol.battery(hex("01 02 01 50 02 50"), true)[1] == 80,
                "OPPO battery event parser already accepts push packets");
        check(EdifierProtocol.battery(0xD0, null) == null && SonyProtocol.battery(1, null) == null
                && TechnicsProtocol.battery(null) == null && RoseProtocol.battery(RoseProtocol.V2, null) == null
                && OppoProtocol.battery(null, true) == null && XiaomiProtocol.battery(null) == null,
                "null battery payloads are never published");
        check(SonyProtocol.mode(1, null) == -1 && TechnicsProtocol.mode(null) == -1
                && OppoProtocol.noiseMask(null) == -1 && XiaomiProtocol.noise(null) == -1,
                "null noise payloads remain unknown");
        check(new EdifierProtocol.Decoder().feed(null, 1).isEmpty()
                && new NiceHckProtocol().feed(null, 1).isEmpty()
                && new RoseProtocol.Decoder(RoseProtocol.V2).feed(null, 1).isEmpty()
                && new OppoProtocol.Decoder().feed(null, 1).isEmpty()
                && new XiaomiProtocol.Decoder().feed(null, 1).isEmpty()
                && new SonyProtocol.Decoder().feed(null).isEmpty()
                && new TechnicsProtocol.Decoder().feed(null).isEmpty(), "null decoder chunks are harmless");
        check(new VivoProtocol.Decoder().feed(vivo, vivo.length + 1).isEmpty()
                && new NiceHckProtocol().feed(nice, nice.length + 1).isEmpty()
                && new OppoProtocol.Decoder().feed(oppo, oppo.length + 1).isEmpty()
                && new RoseProtocol.Decoder(RoseProtocol.V2).feed(rose, rose.length + 1).isEmpty()
                && new EdifierProtocol.Decoder().feed(new byte[1], 2).isEmpty()
                && new XiaomiProtocol.Decoder().feed(new byte[1], 2).isEmpty(), "invalid chunk lengths are rejected before mutation");
        for (byte[] invalid : new byte[][]{null, new byte[0], new byte[15], new byte[17]}) {
            boolean rejected = false;
            try { XiaomiAuth.e21(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "authentication validates exact challenge size");
        }
        DeviceState state = new DeviceState();
        state.begin("A", "Known earbuds", TwsClient.CAP_BATTERY, TwsClient.CAP_NOISE);
        state.begin("A", null, TwsClient.CAP_BATTERY_SINGLE, TwsClient.CAP_NOISE);
        check("Known earbuds".equals(state.name) && state.capability(TwsClient.CAP_NOISE) == DeviceState.UNKNOWN,
                "same-device fallback keeps only a disabled ANC candidate and name");
        state.begin("B", null, TwsClient.CAP_BATTERY_SINGLE, TwsClient.CAP_BATTERY_SINGLE);
        check(state.name.isEmpty() && state.capability(TwsClient.CAP_NOISE) == DeviceState.UNSUPPORTED,
                "new identity cannot inherit remembered name or ANC");
        java.util.Random random = new java.util.Random(20261117L);
        for (int i = 0; i < 128; i++) {
            byte[] bytes = new byte[i * 17]; random.nextBytes(bytes);
            new VivoProtocol.Decoder().feed(bytes, bytes.length);
            new EdifierProtocol.Decoder().feed(bytes, bytes.length);
            new NiceHckProtocol().feed(bytes, bytes.length);
            new OppoProtocol.Decoder().feed(bytes, bytes.length);
            new RoseProtocol.Decoder(RoseProtocol.V2).feed(bytes, bytes.length);
            new SonyProtocol.Decoder().feed(bytes);
            new TechnicsProtocol.Decoder().feed(bytes);
            new XiaomiProtocol.Decoder().feed(bytes, bytes.length);
            check(true, "deterministic malformed byte streams terminate without exceptions");
        }
    }

    private static void soundEffects() {
        check(VivoProtocol.spatial(hex("00"), true) == null, "spatial ACK-only is not off state");
        check(Boolean.FALSE.equals(VivoProtocol.spatial(hex("00"), false)), "spatial query single-byte off");
        check(VivoProtocol.spatial(hex("00 01"), true) == null, "spatial ACK with an echoed target cannot confirm a switch");
        check(Boolean.TRUE.equals(VivoProtocol.spatial(hex("00 01 01"), false)), "Air3 Pro extended report enables spatial capability");
        check(Boolean.FALSE.equals(VivoProtocol.spatial(hex("00 00 01"), false)), "extended report state is second byte, not extension");
        check(VivoProtocol.spatial(hex("00 01 00"), true) == null, "extended ACK cannot confirm a switch or expose sound scenes");
        check(VivoProtocol.spatial(hex("01 01 01"), false) == null, "failed extended report is not a spatial confirmation");
        check(VivoProtocol.spatial(hex("00 99 01"), false) == null, "unknown spatial report format rejected");
        check(SoundPreset.vivo()[4].id == 5, "audiobook uses wire preset 5, never 4");
        check(HuaweiSoundProtocol.supportsQuery("HUAWEI FreeBuds 6i") && HuaweiSoundProtocol.supportsQuery("FreeBuds 5i"),
                "Huawei EQ exact documented model scope");
        check(!HuaweiSoundProtocol.supportsQuery("FreeBuds Pro 6") && !HuaweiSoundProtocol.supportsQuery("FreeBuds 6"),
                "Huawei EQ not inferred for unrelated models");
        byte[] report = hex("01 01 00 02 01 02 03 06 01 02 03 09 02 7F");
        HuaweiSoundProtocol.State state = HuaweiSoundProtocol.decode(report, 0, report.length);
        check(state != null && state.current == 2 && state.presets.length == 4, "EQ uses reported known presets, deduplicates");
        check(state.presets[3].id == 9, "Huawei vocals wire ID");
        byte[] framed = concat(hex("AA BB"), report, hex("CC"));
        check(HuaweiSoundProtocol.decode(framed, 2, report.length) != null, "EQ respects frame payload slice");
        for (int length = 0; length < report.length; length++)
            check(HuaweiSoundProtocol.decode(report, 0, length) == null, "truncated EQ TLV cannot enable writes");
        for (String invalid : new String[]{"02 02 01 02 03 01 01", "02 01 01 02 01 02 03 01 01",
                "02 01 01 03 01 01 03 01 02", "02 01 01 03 01 7F", "02 01 01"}) {
            byte[] bytes = hex(invalid);
            check(HuaweiSoundProtocol.decode(bytes, 0, bytes.length) == null, "invalid EQ capability is not writable");
        }
        check(HuaweiSoundProtocol.decode(report, -1, 2) == null && HuaweiSoundProtocol.decode(report, 1, Integer.MAX_VALUE) == null,
                "EQ slice bounds reject overflow");
    }
    private static void edifier() {
        equal(hex("AA EC D0 00 00 66"), EdifierProtocol.encode(0xD0, new byte[0], true, true), "BES V2 battery query");
        equal(hex("AA 01 D0 21 94"), EdifierProtocol.encode(0xD0, new byte[0], false, false), "BES V1 length");
        equal(hex("AA EC C1 00 02 B5 A1 AF"), EdifierProtocol.encode(0xC1, hex("10 04"), true, true), "BES ambient command");
        byte[] frame = hex("BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC");
        for (int split = 0; split <= frame.length; split++) {
            EdifierProtocol.Decoder decoder = new EdifierProtocol.Decoder();
            List<EdifierProtocol.Frame> decoded = new ArrayList<EdifierProtocol.Frame>();
            decoded.addAll(decoder.feed(Arrays.copyOf(frame, split), split));
            decoded.addAll(decoder.feed(Arrays.copyOfRange(frame, split, frame.length), frame.length - split));
            check(decoded.size() == 1, "BES split " + split);
            equal(new int[]{100, 98, 255, -1}, EdifierProtocol.battery(0xF2, decoded.get(0).payload(true)), "Evo Pro capture");
        }
        EdifierProtocol.Decoder decoder = new EdifierProtocol.Decoder();
        byte[] noisy = concat(hex("00 BB EC D0 00 01 99 12"), hex("BB EC D0 00 01 99 11"), hex("BB 02 CC 01 21 A3"));
        List<EdifierProtocol.Frame> frames = decoder.feed(noisy, noisy.length);
        check(frames.size() == 2, "BES bad CRC recovery and concatenation");
        equal(new int[]{60, 255, 255, -1}, EdifierProtocol.battery(0xD0, frames.get(0).payload(true)), "W860NB Pro capture");
        equal(hex("01"), frames.get(1).payload(false), "BES V1 response");
        byte[] turbo = hex("BB EC F2 00 06 03 64 64 00 03 11 7E");
        frames = decoder.feed(turbo, turbo.length);
        equal(new int[]{100, 100, 255, -1}, EdifierProtocol.battery(0xF2, frames.get(0).payload(false)), "FitBuds Turbo plaintext");
        equal(new int[]{255, 50, 255, -1}, EdifierProtocol.battery(0xF2, hex("03 00 32 00 03 11")), "BES missing ear");
        equal(new int[]{0, 255, 255, -1}, EdifierProtocol.battery(0xD0, hex("00")), "BES real zero");
        check(EdifierProtocol.battery(0xF2, hex("03 FF 32 00 03 11")) == null, "BES invalid percentage");
        check(EdifierProtocol.mode(0x10, 3) == -1, "wind is not OFF");
        check(EdifierProtocol.mode(0x1B, 6) == TwsClient.MODE_OFF, "Evo off");
        equal(hex("1B 01"), EdifierProtocol.modeCommand(true, 0x1B, TwsClient.MODE_ANC), "Evo ANC");
        check(EdifierProtocol.modeCommand(true, 0x99, TwsClient.MODE_OFF) == null, "unknown dialect not writable");
    }
    private static void xiaomi() {
        equal(hex("FE DC BA C4 02 00 05 01 FF FF FF FF EF"),
                XiaomiProtocol.command(2, 4, 1, hex("FF FF FF FF")), "RCSP query");
        byte[] frame = XiaomiProtocol.response(2, 7, 1, 0, hex("04 07 E4 E4 32"));
        for (int split = 0; split <= frame.length; split++) {
            XiaomiProtocol.Decoder decoder = new XiaomiProtocol.Decoder();
            List<byte[]> frames = new ArrayList<byte[]>();
            frames.addAll(decoder.feed(Arrays.copyOf(frame, split), split));
            frames.addAll(decoder.feed(Arrays.copyOfRange(frame, split, frame.length), frame.length - split));
            check(frames.size() == 1, "RCSP split " + split);
            equal(frame, frames.get(0), "RCSP preserved frame");
        }
        byte[] emptyCommand = hex("FE DC BA 84 04 00 01 0A EF");
        XiaomiProtocol.Decoder decoder = new XiaomiProtocol.Decoder();
        check(decoder.feed(emptyCommand, emptyCommand.length).size() == 1, "RCSP one-byte command");
        byte[] bad = frame.clone(); bad[bad.length - 1] = 0;
        byte[] joined = concat(hex("00 FE DC 00"), bad, frame, frame);
        check(new XiaomiProtocol.Decoder().feed(joined, joined.length).size() == 2, "RCSP resync and concatenation");
        equal(new int[]{100, 100, 50, 3}, XiaomiProtocol.battery(hex("04 07 E4 E4 32")), "Redmi live capture");
        equal(new int[]{97, 99, 255, 0}, XiaomiProtocol.battery(hex("04 07 61 63 FF")), "unknown case is not charging");
        equal(new int[]{0, 0, 0, 1}, XiaomiProtocol.battery(hex("04 07 80 00 00")), "real zero percent");
        check(XiaomiProtocol.battery(hex("04 07 7F 63 FF")) == null, "bad battery value");
        check(XiaomiProtocol.battery(hex("04 07 61 63")) == null, "truncated TLV");
        check(XiaomiProtocol.battery(hex("04 07 61 63 FF 09")) == null, "trailing broken TLV");
        check(XiaomiProtocol.noise(hex("04 00 0B 01 00")) == 1, "Redmi ANC");
        check(XiaomiProtocol.noise(hex("04 00 0B 03 00")) == -1, "unknown noise is not OFF");
        check(XiaomiProtocol.noise(hex("03 00 0B 00")) == -1, "placeholder config is not state");
        check(XiaomiProtocol.noise(hex("FF 00 34 00 02 00 00 04 00 0B 02 00")) == 2, "extended config TLV");
        check(XiaomiProtocol.profile("Redmi Buds 5") == 1, "Redmi 5 unauthenticated config dialect");
        check(XiaomiProtocol.profile("Redmi Buds 3 Pro") == 2, "Redmi 3 Pro authenticated config dialect");
        check(XiaomiProtocol.profile("Redmi Buds 6 Lite") == 0, "Redmi 6 Lite target-info dialect");
        check(XiaomiProtocol.profile("Redmi Buds 5 Pro") == 0, "do not infer Buds 5 Pro from Buds 5");
        check(XiaomiProtocol.authApp(2) == 4 && XiaomiProtocol.authApp(0) == 1, "Redmi model-specific auth app");
        byte[] challenge = hex(XiaomiAuth.VEC_IN);
        equal(hex(XiaomiAuth.VEC_OUT), XiaomiAuth.e21(challenge), "E21 published vector");
        byte[] answer = concat(hex("01"), hex(XiaomiAuth.VEC_OUT));
        check(XiaomiProtocol.authMatches(challenge, answer), "authentication accepted");
        answer[3] ^= 1;
        check(!XiaomiProtocol.authMatches(challenge, answer), "mismatched authentication rejected");
        check(!XiaomiProtocol.authMatches(challenge, hex("01")), "short authentication rejected");
        check(XiaomiProtocol.isVerifiedReal("Redmi Buds 6") && XiaomiProtocol.isVerifiedReal("红米 Buds 6")
                && !XiaomiProtocol.isVerifiedReal("Redmi Buds 6 Lite") && !XiaomiProtocol.isVerifiedReal("Redmi Buds 5"),
                "Xiaomi real-device scope");
    }
    private static void rose() {
        equal(hex("FF 00 0F FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33 AA"),
                RoseProtocol.query(RoseProtocol.RS, 99), "RS exact OpenFun query");
        equal(hex("FF 00 1E FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 2F 31 32 33 36 37 38 39 3A 3B 3C 3D 3F 45 46 49 E9 AA"),
                RoseProtocol.query(RoseProtocol.V2, 0), "V2 checksum is not a query ID");
        equal(hex("FF 00 02 09 01 0B AA"), RoseProtocol.encode(RoseProtocol.V2, 0, hex("09 01")), "V2 no extra zero");
        check(RoseProtocol.familyForName("ROSE OpenFun") == RoseProtocol.RS, "OpenFun RS profile");
        check(RoseProtocol.familyForName("roseselsa CERAMICS-U") == RoseProtocol.V2, "Ceramics U V2 profile");
        check(RoseProtocol.familyForName("CERAMICS-X") == -1, "F8 never guessed as V2");
        check(RoseProtocol.familyForName("SportFeel") == -1, "ambiguous chip profile rejected");
        check(!RoseProtocol.supportsNoise("OpenFun"), "open-ear model has no ANC controls");
        byte[] rs = hex("DD 00 04 0C 40 3D 00 AA");
        byte[] v2 = hex("DD 00 04 0C E4 63 FF 02 09 01 3F AA");
        for (int family = 0; family <= 1; family++) {
            byte[] frame = family == RoseProtocol.RS ? rs : v2;
            for (int split = 0; split <= frame.length; split++) {
                RoseProtocol.Decoder decoder = new RoseProtocol.Decoder(family);
                List<RoseProtocol.Item> items = new ArrayList<RoseProtocol.Item>();
                items.addAll(decoder.feed(Arrays.copyOf(frame, split), split));
                items.addAll(decoder.feed(Arrays.copyOfRange(frame, split, frame.length), frame.length - split));
                check(items.size() == (family == RoseProtocol.RS ? 1 : 2), "Rose half packet / seq zero");
            }
        }
        equal(new int[]{64, 61, 0, 0}, RoseProtocol.battery(RoseProtocol.RS, hex("40 3D 00")), "OpenFun capture");
        equal(new int[]{100, 99, 255, 1}, RoseProtocol.battery(RoseProtocol.V2, hex("E4 63 FF")), "Rose charging and unavailable");
        byte[] bad = v2.clone(); bad[bad.length - 2] ^= 1;
        byte[] stream = concat(bad, v2, v2);
        check(new RoseProtocol.Decoder(RoseProtocol.V2).feed(stream, stream.length).size() == 4, "Rose checksum recovery");
        byte[] embedded = hex("DD 00 04 0C AA 3D 00 AA");
        check(new RoseProtocol.Decoder(RoseProtocol.RS).feed(embedded, embedded.length).size() == 1, "Rose payload AA is not terminator");
    }

    private static void niceHck() {
        byte[] frame = hex("4E 06 00 00 05 00 50 4A 64");
        for (int split = 0; split <= frame.length; split++) {
            NiceHckProtocol decoder = new NiceHckProtocol();
            List<byte[]> frames = new ArrayList<byte[]>();
            frames.addAll(decoder.feed(Arrays.copyOf(frame, split), split));
            frames.addAll(decoder.feed(Arrays.copyOfRange(frame, split, frame.length), frame.length - split));
            check(frames.size() == 1, "NiceHCK split");
        }
        byte[] stream = concat(new byte[20000], hex("4E FF FF"), frame, frame);
        check(new NiceHckProtocol().feed(stream, stream.length).size() == 2, "NiceHCK garbage and oversized lengths");
    }

    private static void oppo() {
        byte[] frame = hex("AA 0C 00 00 0C 81 22 05 00 00 01 01 10 00");
        for (int split = 0; split <= frame.length; split++) {
            OppoProtocol.Decoder decoder = new OppoProtocol.Decoder();
            List<OppoProtocol.Frame> frames = new ArrayList<OppoProtocol.Frame>();
            frames.addAll(decoder.feed(Arrays.copyOf(frame, split), split));
            frames.addAll(decoder.feed(Arrays.copyOfRange(frame, split, frame.length), frame.length - split));
            check(frames.size() == 1 && frames.get(0).command == 0x810C, "OPO split");
        }
        byte[] bad = frame.clone(); bad[7] = 6;
        byte[] stream = concat(hex("AA 80 80 80"), bad, frame);
        check(new OppoProtocol.Decoder().feed(stream, stream.length).size() == 1, "OPO overflow and cross-frame payload rejected");
        equal(new int[]{80, 80, 255, 0}, OppoProtocol.battery(hex("00 02 01 50 02 50"), false), "Enco Air3 Pro capture");
        equal(new int[]{0, 255, 255, 1}, OppoProtocol.battery(hex("00 02 01 80 02 FF"), false), "OPO zero and unknown");
        check(OppoProtocol.battery(hex("01 02 01 50 02 50"), false) == null, "OPO error response not state");
        check(OppoProtocol.battery(hex("01 02 01 50 02"), true) == null, "OPO truncated event");
        check(OppoProtocol.noiseMask(hex("00 01 01 10 00")) == 16, "OPO status-prefixed mode");
        check(OppoProtocol.noiseMask(hex("01 01 00 01")) == 256, "OPO legacy mode");
        check(OppoProtocol.noiseMask(hex("09 09 10 00")) == -1, "OPO arbitrary tail not mode");
        check(OppoProtocol.noiseMode(0x10) == TwsClient.MODE_ANC, "Enco Air3 Pro ANC feedback");
        check(OppoProtocol.noiseMode(0x02) == TwsClient.MODE_ANC, "legacy OPO ANC feedback");
        check(OppoProtocol.noiseMode(0x18) == -1, "ambiguous OPO mode rejected");
        check(OppoProtocol.isVerifiedReal("OPPO Enco Air5 Pro") && OppoProtocol.isVerifiedReal("OPPO Enco Free4")
                && !OppoProtocol.isVerifiedReal("Enco Air3 Pro"), "OPPO real-device scope");
    }

    private static void sony() {
        equal(hex("3E 0C 00 00 00 00 02 00 00 0E 3C"),
                SonyProtocol.encode(12, 0, hex("00 00")), "MDR hello");
        equal(hex("3E 01 01 00 00 00 00 02 3C"),
                SonyProtocol.encode(1, 1, new byte[0]), "MDR ACK opposite sequence");
        equal(hex("3E 0C 00 00 00 00 03 3D 2E 3D 2C 3D 2D C6 3C"),
                SonyProtocol.encode(12, 0, hex("3E 3C 3D")), "MDR escaped bytes");
        byte[] frame = hex("3E 0C 00 00 00 00 07 69 17 01 01 01 00 0A A0 3C");
        for (int split = 0; split <= frame.length; split++) {
            SonyProtocol.Decoder decoder = new SonyProtocol.Decoder();
            List<SonyProtocol.Frame> frames = new ArrayList<SonyProtocol.Frame>();
            frames.addAll(decoder.feed(Arrays.copyOf(frame, split)));
            frames.addAll(decoder.feed(Arrays.copyOfRange(frame, split, frame.length)));
            check(frames.size() == 1 && SonyProtocol.mode(2, frames.get(0).payload) == TwsClient.MODE_TRANS,
                    "MDR capture fragmented");
        }
        byte[] bad = frame.clone(); bad[bad.length - 2] ^= 1;
        byte[] stream = concat(hex("00 3E 0C 3D 00"), bad, frame, frame);
        check(new SonyProtocol.Decoder().feed(stream).size() == 2, "MDR checksum and escaping recovery");
        byte[] escaped = SonyProtocol.encode(12, 1, hex("3E 3C 3D"));
        for (int split = 0; split <= escaped.length; split++) {
            SonyProtocol.Decoder decoder = new SonyProtocol.Decoder();
            List<SonyProtocol.Frame> frames = new ArrayList<SonyProtocol.Frame>();
            frames.addAll(decoder.feed(Arrays.copyOf(escaped, split)));
            frames.addAll(decoder.feed(Arrays.copyOfRange(escaped, split, escaped.length)));
            check(frames.size() == 1, "MDR escape split");
            equal(hex("3E 3C 3D"), frames.get(0).payload, "MDR decoded escapes");
        }
        check(SonyProtocol.generation(hex("01 00 03 00 10 02 00 00")) == 2, "MDR V2 detection");
        check(SonyProtocol.generation(hex("01 00 01 00")) == 1, "MDR V1 detection");
        equal(new int[]{50, 70, 255, 1}, SonyProtocol.battery(2, hex("23 01 32 01 46 00")), "MDR left right");
        check(SonyProtocol.battery(2, hex("23 01 32 01 46")) == null, "MDR truncated battery");
        check(SonyProtocol.battery(2, hex("23 00 7F 00")) == null, "MDR impossible percentage");
        equal(hex("68 17 01 01 01 00 14"),
                SonyProtocol.noiseCommand(2, hex("67 17 01 01 00 00 14"), TwsClient.MODE_TRANS), "MDR preserve ambient level");
        equal(hex("68 19 01 00 00 01 0A 01 02"),
                SonyProtocol.noiseCommand(2, hex("69 19 00 01 00 01 0A 01 02"), TwsClient.MODE_OFF), "MDR preserve extra fields");
        check(SonyProtocol.noiseCommand(2, hex("67 22 01 01 00 14"), TwsClient.MODE_ANC) == null, "ambient-only is not ANC");
        check(SonyProtocol.mode(1, hex("67 02 01 02 02 01 00 00")) == TwsClient.MODE_ANC, "MDR V1 ANC");
        check(SonyProtocol.mode(2, hex("67 17 01 01 02 00 14")) == -1, "unknown MDR mode");
        check(SonyProtocol.matches("Sony WH-CH720N") && SonyProtocol.matches("WF-1000XM5")
                && SonyProtocol.matches("LE_WF-1000XM5") && !SonyProtocol.matches("Sony TV"), "Sony candidate boundaries");
        check(SonyProtocol.isVerifiedReal("WF-1000XM5") && SonyProtocol.isVerifiedReal("LE_WF-1000XM5")
                && !SonyProtocol.isVerifiedReal("WH-CH720N"), "Sony real-device scope");
    }

    private static void technics() {
        equal(hex("05 5A 03 00 D6 0C 00"), TechnicsProtocol.command(0x0CD6, (byte) 0), "RACE right query");
        byte[] frame = hex("05 5D 05 00 D6 0C 00 01 64");
        for (int split = 0; split <= frame.length; split++) {
            TechnicsProtocol.Decoder decoder = new TechnicsProtocol.Decoder();
            List<byte[]> frames = new ArrayList<byte[]>();
            frames.addAll(decoder.feed(Arrays.copyOf(frame, split)));
            frames.addAll(decoder.feed(Arrays.copyOfRange(frame, split, frame.length)));
            check(frames.size() == 1, "RACE split");
            equal(new int[]{0, 100}, TechnicsProtocol.battery(frames.get(0)), "RACE agent/client mapping");
        }
        check(TechnicsProtocol.battery(hex("05 5B 03 00 D6 0C 00")) == null, "RACE ACK is not battery");
        check(TechnicsProtocol.battery(hex("05 5D 05 00 D6 0C 01 00 64")) == null, "RACE error not data");
        equal(new int[]{2, 0}, TechnicsProtocol.battery(hex("05 5B 04 00 40 00 00 00")), "RACE real empty case");
        check(TechnicsProtocol.mode(hex("05 5B 06 00 0A 00 00 02 64 32")) == TwsClient.MODE_TRANS, "RACE transparency");
        check(TechnicsProtocol.mode(hex("05 5B 06 00 0A 00 00 04 64 32")) == -1, "RACE unknown mode");
        byte[][] commands = TechnicsProtocol.noiseCommands(TwsClient.MODE_ANC);
        equal(hex("05 5A 03 00 68 00 00"), commands[0], "RACE adaptive off");
        equal(hex("05 5A 05 00 0B 00 01 64 32"), commands[1], "RACE ANC write");
        equal(hex("05 5A 04 00 39 00 20 02"), commands[2], "RACE ANC sequence end");
        check(TechnicsProtocol.matches("Technics EAH-AZ80") && TechnicsProtocol.matches("AZ100")
                && !TechnicsProtocol.matches("EAH-A800"), "Technics TWS only");
        byte[] stream = concat(hex("05 5D FF FF"), frame, frame);
        check(new TechnicsProtocol.Decoder().feed(stream).size() == 2, "RACE oversized frame recovery");
    }

    private static void vivo() {
        equal(new int[]{83, 82, 95, 0}, VivoProtocol.battery(hex("00 53 52 5F 00")), "Air3 Pro public capture");
        equal(new int[]{0, 100, 255, 7}, VivoProtocol.battery(hex("00 00 64 FF 07")), "GAIA zero and missing component");
        equal(new int[]{50, 50, 50, -1}, VivoProtocol.battery(hex("00 32 32 32 FF")), "GAIA unknown charging");
        check(VivoProtocol.battery(hex("01 53 52 5F 00")) == null, "GAIA rejected response is not battery");
        check(VivoProtocol.battery(hex("00 65 52 5F 00")) == null, "GAIA percentage over 100");
        equal(new int[]{83, 82, 95, -1}, VivoProtocol.battery(hex("00 53 52 5F 08")),
                "GAIA undefined flags do not discard valid battery");
        check(VivoProtocol.battery(hex("00 53 52 5F")) == null, "GAIA truncated battery");
        check(VivoProtocol.noise(hex("00 00 04 00")) == TwsClient.MODE_ANC, "GAIA ANC capture");
        check(VivoProtocol.noise(hex("00 01 04 00")) == TwsClient.MODE_OFF, "GAIA OFF capture");
        check(VivoProtocol.noise(hex("00 02 04 00")) == TwsClient.MODE_TRANS, "GAIA transparency capture");
        check(VivoProtocol.noise(hex("01 00")) == null, "GAIA rejection is not ANC");
        check(VivoProtocol.noise(hex("00")) == null, "GAIA short state");
        check(VivoProtocol.noise(hex("00 FF")) == -1, "GAIA nonstandard mode clears old selection");
        check(Boolean.TRUE.equals(VivoProtocol.spatial(hex("00 01"))), "GAIA spatial on");
        check(Boolean.FALSE.equals(VivoProtocol.spatial(hex("00"))), "GAIA single byte spatial off");
        check(VivoProtocol.spatial(hex("00 02")) == null && VivoProtocol.spatial(hex("01 01")) == null,
                "GAIA invalid spatial state and rejection");
        check(VivoProtocol.deepX(hex("00 05")) == 5, "GAIA DeepX audiobook uses 5");
        check(VivoProtocol.deepX(hex("00 04")) == null && VivoProtocol.deepX(hex("01 00")) == null,
                "GAIA unknown and rejected DeepX");
    }

    private static void scrollFeedback() {
        ScrollFeedback feedback = new ScrollFeedback(8f);
        check(feedback.scrolled(3, 0, 100, 0) == ScrollFeedback.NONE, "scroll fractional distance");
        check(feedback.scrolled(8, 3, 100, 30) == ScrollFeedback.TICK, "scroll distance accumulates");
        check(feedback.scrolled(80, 8, 100, 60) == ScrollFeedback.TICK, "merged scroll has one tick");
        check(feedback.scrolled(81, 80, 100, 61) == ScrollFeedback.NONE, "no queued ticks after merged event");
        check(feedback.scrolled(89, 81, 100, 65) == ScrollFeedback.NONE, "ticks respect minimum spacing");
        check(feedback.scrolled(100, 89, 100, 70) == ScrollFeedback.LIMIT, "wall overrides tick immediately");
        check(feedback.atLimit(), "wall latch set");
        check(feedback.limit(1, 80) == ScrollFeedback.NONE, "holding edge does not repeat wall");
        check(feedback.scrolled(90, 100, 100, 90) == ScrollFeedback.NONE, "reverse cannot interrupt wall");
        check(!feedback.atLimit(), "leaving edge unlocks wall");
        check(feedback.scrolled(80, 90, 100, 200) == ScrollFeedback.TICK, "ticks resume after wall");
        check(feedback.scrolled(0, 80, 100, 230) == ScrollFeedback.LIMIT, "top wall works too");
        check(feedback.limit(-1, 360) == ScrollFeedback.NONE, "top wall not repeated");
        feedback.reset();
        check(feedback.scrolled(0, 0, 0, 400) == ScrollFeedback.NONE, "short page has no feedback");
        check(feedback.scrolled(0, 0, 100, 400) == ScrollFeedback.NONE, "stationary page has no feedback");
        check(feedback.scrolled(8, 0, 100, 400) == ScrollFeedback.TICK, "new page has no previous cooldown");
        feedback.beginGesture();
        check(feedback.limit(-1, 500) == ScrollFeedback.LIMIT, "new outward drag has wall feedback");
    }

    private static void indicatorMotion() {
        ScrollIndicatorMotion motion = new ScrollIndicatorMotion();
        motion.reset(0f);
        check(!motion.advance(0f, 0), "idle indicator does not animate");
        motion.pulse(0);
        float peak = 0f, largest = 1f;
        boolean running = false, bounded = true;
        for (int time = 16; time <= 2000; time += 16) {
            running = motion.advance(0.5f, time);
            peak = Math.max(peak, motion.position());
            largest = Math.max(largest, motion.stretch());
            bounded &= motion.position() >= 0f && motion.position() <= 1f;
        }
        check(peak > 0.52f, "indicator has visible elastic overshoot");
        check(largest > 1.15f, "indicator visibly stretches instead of rigid tracking");
        check(bounded && !running && motion.position() == 0.5f && motion.stretch() == 1f,
                "indicator settles without continuous redraws");
        float slow = motionPeak(33), fast = motionPeak(8);
        check(Math.abs(slow - peak) < 0.025f && Math.abs(fast - peak) < 0.025f,
                "indicator bounce consistent at 30/60/120Hz");
        motion.reset(1f);
        motion.pulse(3000);
        check(motion.advance(1f, 3080) && motion.position() == 1f && motion.stretch() > 1.05f,
                "wall pulse bounces without leaving the rail");
        check(!motion.advance(1f, 3500) && motion.stretch() == 1f,
                "wall pulse ends by itself");
        motion.reset(0.75f);
        check(!motion.advance(0.75f, 3600) && motion.stretch() == 1f,
                "pause reset clears old bounce");
        motion.pulse(3600);
        for (int time = 3616; time < 5600; time += 16) motion.advance(0f, time);
        check(motion.position() == 0f && motion.stretch() == 1f,
                "reverse scroll reaches top and settles");
    }

    private static float motionPeak(int interval) {
        ScrollIndicatorMotion motion = new ScrollIndicatorMotion();
        motion.reset(0f); motion.pulse(0);
        float peak = 0f;
        for (int time = interval; time < 2000; time += interval) {
            motion.advance(0.5f, time);
            peak = Math.max(peak, motion.position());
        }
        return peak;
    }

    private static void snapshots() {
        TileSnapshot unknown = new TileSnapshot("Buds", 0, 50, 51, 52, -1, -1, -1,
                TwsClient.CAP_NOISE, TwsClient.VERIF_UNTESTED);
        check(unknown.mode == -1 && unknown.confirmedMode == -1, "unknown mode has no stale selection");
        TileSnapshot pending = new TileSnapshot("Buds", 0, 50, 51, 52, -1, -1, -1,
                TwsClient.CAP_NOISE, TwsClient.VERIF_UNTESTED, TwsClient.MODE_ANC,
                System.currentTimeMillis() + 10000L);
        check(pending.mode == TwsClient.MODE_ANC && pending.confirmedMode == -1,
                "optimistic target remains separate from unknown confirmation");
        TileSnapshot expired = new TileSnapshot("Buds", 0, 50, 51, 52, -1, -1, -1,
                TwsClient.CAP_NOISE, TwsClient.VERIF_UNTESTED, TwsClient.MODE_ANC, 1L);
        check(expired.mode == -1, "expired target cannot become confirmed mode");
    }

    private static void writer() throws Exception {
        final ProtocolWriter writer = new ProtocolWriter();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1), newSession = new CountDownLatch(1);
        final AtomicInteger stale = new AtomicInteger();
        final Thread caller = Thread.currentThread();
        final List<Integer> order = new ArrayList<Integer>();
        writer.start();
        try {
            final CountDownLatch ordered = new CountDownLatch(3);
            final boolean[] onWorker = {false};
            for (int i = 0; i < 3; i++) {
                final int value = i;
                check(writer.post(new Runnable() { @Override public void run() {
                    onWorker[0] = Thread.currentThread() != caller;
                    order.add(Integer.valueOf(value)); ordered.countDown();
                }}), "writer accepts ordered task");
            }
            check(ordered.await(3, TimeUnit.SECONDS) && onWorker[0], "writes run away from caller thread");
            check(order.equals(Arrays.asList(0, 1, 2)), "write order preserved");
            check(writer.post(new Runnable() { @Override public void run() {
                entered.countDown();
                try {
                    // 模拟关连接后才能结束的阻塞 I/O；忽略中断不等于允许旧排队任务继续运行。
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                } finally { exited.countDown(); }
            }}), "writer accepts blocking transport");
            check(entered.await(3, TimeUnit.SECONDS), "blocking write started");
            int accepted = 0;
            for (int i = 0; i < 32; i++) {
                if (writer.post(new Runnable() { @Override public void run() { stale.incrementAndGet(); }})) accepted++;
            }
            check(accepted == 32, "writer bounded pending capacity");
            check(!writer.post(new Runnable() { @Override public void run() { stale.incrementAndGet(); }}),
                    "writer rejects overflow instead of unbounded growth");
            writer.stop();
            check(!writer.post(new Runnable() { @Override public void run() { stale.incrementAndGet(); }}),
                    "disconnected writer rejects commands");
            writer.start();
            check(writer.post(new Runnable() { @Override public void run() { newSession.countDown(); }}),
                    "new connection has its own writer");
            check(newSession.await(3, TimeUnit.SECONDS), "old blocked write cannot block new session queue");
            release.countDown();
            check(exited.await(3, TimeUnit.SECONDS) && stale.get() == 0, "old pending commands discarded on disconnect");
        } finally { release.countDown(); writer.stop(); }
    }

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    static void equal(byte[] expected, byte[] actual, String label) { check(Arrays.equals(expected, actual), label); }
    static void equal(int[] expected, int[] actual, String label) { check(Arrays.equals(expected, actual), label); }
    static byte[] hex(String value) {
        String clean = value.replace(" ", "");
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    static byte[] concat(byte[]... arrays) {
        int length = 0; for (byte[] array : arrays) length += array.length;
        byte[] result = new byte[length]; int pos = 0;
        for (byte[] array : arrays) { System.arraycopy(array, 0, result, pos, array.length); pos += array.length; }
        return result;
    }
}

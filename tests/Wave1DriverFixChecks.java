package com.hdz.wearcontrol;

import com.hdz.wearcontrol.huawei.HuaweiSoundProtocol;
import com.hdz.wearcontrol.huawei.HuaweiSppClient;
import com.hdz.wearcontrol.oppo.OppoOpoClient;
import com.hdz.wearcontrol.oppo.OppoProtocol;
import com.hdz.wearcontrol.vivo.VivoGaiaClient;
import com.hdz.wearcontrol.vivo.VivoProtocol;
import com.hdz.wearcontrol.xiaomi.XiaomiAuth;
import com.hdz.wearcontrol.xiaomi.XiaomiMmaClient;
import com.hdz.wearcontrol.xiaomi.XiaomiProtocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.hdz.wearcontrol.Controls.*;

/** Regression suite for all Wave 1 brand driver fixes. */
public final class Wave1DriverFixChecks {
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
        final List<String> events = new ArrayList<>();
        int connections, disconnections, errors, failures;
        int mode = -1, left = -1, right = -1, box = -1, charge = -1;
        int connectedCaps = -1;

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
        void close() { driver.onClose(); }
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
        @Override public void onConnected(String name) {
            connections++;
            connectedCaps = driver.caps();
            events.add("connected:" + connectedCaps);
        }
        @Override public void onDisconnected(String reason) { disconnections++; }
        @Override public void onError(String msg) { errors++; }
        @Override public void onBattery(int l, int r, int b, int f) {
            left = l; right = r; box = b; charge = f;
            events.add("battery:" + l + "/" + r + "/" + b + "/" + f);
        }
        @Override public void onNoiseMode(int value) {
            mode = value;
            events.add("mode:" + value + ":" + driver.caps());
        }
        @Override public void onSpatial(Boolean state) { }
        @Override public void onDeepX(Integer mode) { }
    }

    private static void nullSafety() {
        check(InstalledDrivers.ALL.length == 9, "all 9 brand modules registered");
        for (DriverModule m : InstalledDrivers.ALL) {
            check(!m.matches(null), m.getClass().getName() + " matches(null) returns false");
            check(!m.matches(""), m.getClass().getName() + " matches(\"\") returns false");
        }
        check(XiaomiProtocol.profile(null) == 0, "XiaomiProtocol.profile(null) safe");
        check(!XiaomiProtocol.isVerifiedReal(null), "XiaomiProtocol.isVerifiedReal(null) safe");
        check(!OppoProtocol.isVerifiedReal(null), "OppoProtocol.isVerifiedReal(null) safe");
        check(!HuaweiSoundProtocol.supportsQuery(null), "HuaweiSoundProtocol.supportsQuery(null) safe");
    }

    private static void xiaomiFixes() {
        check(XiaomiProtocol.profile("红米 Buds 5") == 1, "Chinese Redmi Buds 5 maps to profile 1");
        check(XiaomiProtocol.profile("红米 Buds 3 Pro") == 2, "Chinese Redmi Buds 3 Pro maps to profile 2");
        check(XiaomiProtocol.profile("小米红米 Buds 5") == 1, "Xiaomi Chinese Redmi Buds 5 maps to profile 1");
        check(XiaomiProtocol.profile("小米 Buds 5") == 0, "Xiaomi Buds 5 stays profile 0");
        check(XiaomiProtocol.profile("红米 Buds 5 Pro") == 0, "Chinese Redmi Buds 5 Pro stays profile 0");

        Rig buds5Cn = new Rig("红米 Buds 5");
        buds5Cn.open();
        check(buds5Cn.frames.size() == 2 && buds5Cn.frames.get(0)[4] == 2 && (buds5Cn.frames.get(1)[4] & 255) == 0xF3,
                "Chinese Redmi Buds 5 uses auth-less profile 1 startup queries");
        buds5Cn.receive(XiaomiProtocol.response(2, 0, 0, 0, hex("04 07 50 5A 32")));
        check(buds5Cn.connections == 1 && buds5Cn.left == 80 && buds5Cn.right == 90 && buds5Cn.box == 50,
                "Chinese Redmi Buds 5 opens on valid battery report");

        Rig buds6 = new Rig("Redmi Buds 6");
        buds6.open();
        int sn = buds6.frames.get(0)[7] & 255;
        byte[] answer = new byte[17];
        answer[0] = 1;
        System.arraycopy(XiaomiAuth.e21(Arrays.copyOfRange(buds6.frames.get(0), 9, 25)), 0, answer, 1, 16);
        buds6.receive(XiaomiProtocol.response(0x50, 1, sn, 0, answer));
        byte[] done = buds6.frames.get(1);
        buds6.receive(XiaomiProtocol.response(0x51, 1, done[7] & 255, 0, new byte[]{1}));
        check(buds6.connections == 1 && (buds6.driver.caps() & CAP_NOISE) == 0, "Buds 6 starts without confirmed ANC");

        // Customized touch cycle 0x000A (mask 0x02 != 0x06) + valid CFG_NOISE 0x000B.
        byte[] customCfg = hex("03 00 0A 02 04 00 0B 01 00");
        int qSn = buds6.frames.get(3)[7] & 255;
        buds6.receive(XiaomiProtocol.response(0xF3, 4, qSn, 0, customCfg));
        check((buds6.driver.caps() & CAP_NOISE) != 0 && buds6.mode == MODE_ANC,
                "Buds 6 confirms CAP_NOISE from CFG_NOISE even when 0x000A cycle is customized");
        int before = buds6.frames.size();
        buds6.driver.changeNoise(MODE_TRANS, () -> { });
        check(buds6.frames.size() == before + 1 && buds6.frames.get(before)[4] == 0x08,
                "Buds 6 writes SET_TARGET_INFO after custom-cycle CFG_NOISE confirmation");

        Rig buds6Play = new Rig("Redmi Buds 6 Play");
        buds6Play.open();
        int pSn = buds6Play.frames.get(0)[7] & 255;
        byte[] pAns = new byte[17];
        pAns[0] = 1;
        System.arraycopy(XiaomiAuth.e21(Arrays.copyOfRange(buds6Play.frames.get(0), 9, 25)), 0, pAns, 1, 16);
        buds6Play.receive(XiaomiProtocol.response(0x50, 1, pSn, 0, pAns));
        byte[] pDone = buds6Play.frames.get(1);
        buds6Play.receive(XiaomiProtocol.response(0x51, 1, pDone[7] & 255, 0, new byte[]{1}));
        int pqSn = buds6Play.frames.get(2)[7] & 255; // no queryNoise on noNoiseModel
        buds6Play.receive(XiaomiProtocol.response(0xF3, 4, pqSn, 0, hex("04 00 0B 01 00")));
        check((buds6Play.driver.caps() & CAP_NOISE) == 0 && (buds6Play.driver.potentialCaps() & CAP_NOISE) == 0,
                "noNoiseModel stays disabled even if 0x000B arrives");
    }

    private static byte[] huaweiFrame(int group, int cmd, byte[] params) {
        int len = params.length + 3;
        byte[] f = new byte[6 + params.length + 2];
        f[0] = 0x5A;
        f[1] = (byte) (len >> 8);
        f[2] = (byte) len;
        f[3] = 0x00;
        f[4] = (byte) group;
        f[5] = (byte) cmd;
        System.arraycopy(params, 0, f, 6, params.length);
        int crc = 0;
        for (int i = 0; i < 6 + params.length; i++) {
            crc ^= (f[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        f[6 + params.length] = (byte) (crc >> 8);
        f[7 + params.length] = (byte) crc;
        return f;
    }

    private static void huaweiFixes() {
        Rig r = new Rig("HUAWEI FreeBuds 6i");
        r.open();

        // TLV 03 per-component charging: only case charging (00 00 01 -> flags=4).
        r.receive(huaweiFrame(0x01, 0x08, hex("02 03 50 5A 64 03 03 00 00 01")));
        check(r.left == 80 && r.right == 90 && r.box == 100 && r.charge == 4,
                "Huawei TLV 03 maps case-only charging to bit 2 (4), not 7");

        // Left + case charging -> bit 0 | bit 2 = 5.
        r.receive(huaweiFrame(0x01, 0x27, hex("02 03 40 41 64 03 03 01 00 01")));
        check(r.charge == 5, "Huawei TLV 03 maps left+case charging to 5");

        // 4-byte TLV 03 clamps to bits 0..2 so charge remains <= 7.
        r.receive(huaweiFrame(0x01, 0x27, hex("02 03 40 41 64 03 04 00 01 00 01")));
        check(r.charge == 2, "Huawei TLV 03 ignores extra bytes beyond bit 2");

        // TLV 01 only (single-battery headset, no TLV 02) + 1-byte TLV 03 -> CAP_BATTERY_SINGLE.
        Rig single = new Rig("HUAWEI FreeBuds 5i");
        single.open();
        single.receive(huaweiFrame(0x01, 0x08, hex("01 01 48 03 01 01")));
        check(single.left == 72 && single.right == 0xFF && single.box == 0xFF && single.charge == 1,
                "Huawei TLV 01 overall battery forwarded with single-byte charging bit 0");
        check((single.driver.caps() & CAP_BATTERY_SINGLE) != 0 && (single.driver.caps() & CAP_BATTERY) == 0,
                "Huawei TLV 01 without TLV 02 enables CAP_BATTERY_SINGLE");

        // TWS frame with TLV 02 [0,0,0] and TLV 01 [65] keeps CAP_BATTERY and forwards overall 65.
        single.receive(huaweiFrame(0x01, 0x08, hex("01 01 41 02 03 00 00 00 03 03 00 00 00")));
        check(single.left == 65 && single.right == 0xFF && single.box == 0xFF
                && (single.driver.caps() & CAP_BATTERY) != 0 && (single.driver.caps() & CAP_BATTERY_SINGLE) == 0,
                "Huawei TLV 02 present keeps TWS CAP_BATTERY and falls back to TLV 01 when L/R/C are 0");

        // False 0x5A with non-zero byte[3] and huge len=0x0300 must be discarded immediately before valid 12B frame.
        byte[] validAnc = hex("5A 00 07 00 2B 2A 01 02 00 01 05 10");
        r.receive(concat(hex("5A 03 00 FF 11 22"), validAnc));
        check(r.mode == MODE_ANC, "Huawei discards false 0x5A when byte[3]!=0 without waiting 773 bytes");

        // Chunk exceeding 2048-byte stage buffer does not drop trailing valid frame.
        byte[] bigJunk = new byte[2200];
        Arrays.fill(bigJunk, (byte) 0x33);
        byte[] bigBat = huaweiFrame(0x01, 0x08, hex("02 03 21 22 23 03 03 00 00 00"));
        r.receive(concat(bigJunk, bigBat));
        check(r.left == 33 && r.right == 34 && r.box == 35,
                "Huawei oversized chunk processes across buffer boundary without discarding valid tail");
    }

    private static void niceHckFixes() {
        Rig r = new Rig("NiceHCK OriG in");
        r.open();
        r.receive("4E 06 00 00 05 00 00 55 00");
        check(r.left == 0xFF && r.right == 85 && r.box == 0xFF && r.charge == -1,
                "NiceHCK maps 0 to 0xFF unknown and passes chargeFlags=-1");
        check(DeviceState.lowComponent(new int[]{r.left, r.right, r.box}, r.charge) == -1,
                "NiceHCK absent 0-reported bud does not trigger 0% low battery warning");
    }

    private static void vivoFixes() {
        Rig r = new Rig("vivo TWS Air3 Pro");
        r.open();
        // Valid non-standard mode report (e.g. wind noise 0xFF) confirms CAP_NOISE while reporting -1.
        r.receive("FF 04 00 02 00 1B 82 30 00 FF");
        check(r.mode == -1 && (r.driver.caps() & CAP_NOISE) != 0,
                "Vivo valid non-standard mode (-1) enables CAP_NOISE");
        int before = r.frames.size();
        r.driver.setNoiseMode(MODE_OFF);
        check(r.frames.size() == before + 1, "Vivo allows switching out of non-standard mode");

        // After normal ANC confirmation, subsequent wind mode (-1) must NOT wipe CAP_NOISE.
        r.receive("FF 04 00 04 00 1B 82 30 00 00 04 00");
        check(r.mode == MODE_ANC && (r.driver.caps() & CAP_NOISE) != 0, "Vivo confirms ANC");
        r.receive("FF 04 00 02 00 1B 82 30 00 03");
        check(r.mode == -1 && (r.driver.caps() & CAP_NOISE) != 0,
                "Vivo non-standard wind mode report keeps CAP_NOISE intact");

        // Rejected noise payload (status != 0) must NOT confirm CAP_NOISE.
        Rig rej = new Rig("vivo TWS Air3 Pro");
        rej.open();
        rej.receive("FF 04 00 02 00 1B 82 30 01 00");
        check((rej.driver.caps() & CAP_NOISE) == 0, "Vivo rejected noise payload does not confirm CAP_NOISE");
    }

    private static void oppoFixes() {
        for (int mask : new int[]{0x0002, 0x0010, 0x0020, 0x0040, 0x0080, 0x0800}) {
            check(OppoProtocol.noiseMode(mask) == MODE_ANC, "OPPO ANC mask 0x" + Integer.toHexString(mask));
        }
        check(OppoProtocol.noiseMode(0x0008) == MODE_OFF, "OPPO OFF mask");
        check(OppoProtocol.noiseMode(0x0100) == MODE_TRANS && OppoProtocol.noiseMode(0x0200) == MODE_TRANS
                && OppoProtocol.noiseMode(0x0300) == MODE_TRANS, "OPPO Trans masks");
        check(OppoProtocol.noiseMode(0x0028) == -1 && OppoProtocol.noiseMode(0x0208) == -1
                && OppoProtocol.noiseMode(0x0120) == -1, "OPPO conflicting masks rejected");

        DriverModule oppoMod = null;
        for (DriverModule m : InstalledDrivers.ALL) if (m.matches("OPPO Enco Free4")) oppoMod = m;
        check(oppoMod != null, "OPPO module found");
        check(oppoMod.matches("EncoFree4") && oppoMod.matches("Enco Air5 Pro")
                && oppoMod.matches("OnePlus Buds 3") && oppoMod.matches("realme Buds Air 5"),
                "OPPO matches Enco/OnePlus/realme");
        check(!oppoMod.matches("JBL PartyBox Encore") && !oppoMod.matches("JBL Encore Essential"),
                "OPPO does not false-match Encore speakers");

        // Silent handshake (all 10 commands sent and flushed, zero responses) must fail.
        Rig silent = new Rig("OPPO Enco Free4");
        silent.open();
        for (int i = 0; i < 10; i++) {
            silent.clock.tick(i == 0 ? 300 : 200);
            silent.flushLast();
        }
        silent.clock.tick(400);
        check(silent.connections == 0 && silent.failures == 1 && !silent.driver.isConnected(),
                "OPPO silent handshake fails instead of reporting connected");

        // Ready-vs-onNoiseMode ordering: CAP_NOISE is false during onConnected, true as initial onNoiseMode fires.
        Rig ok = new Rig("OPPO Enco Free4");
        ok.open();
        for (int i = 0; i < 10; i++) {
            ok.clock.tick(i == 0 ? 300 : 200);
            if (i == 5) ok.receive("AA 0C 00 00 0C 81 22 05 00 00 01 01 10 00");
            ok.flushLast();
        }
        check((ok.driver.caps() & CAP_NOISE) == 0, "OPPO holds CAP_NOISE until markReady emits initial mode");
        ok.clock.tick(400);
        check(ok.connections == 1 && (ok.connectedCaps & CAP_NOISE) == 0
                && ok.mode == MODE_ANC && (ok.driver.caps() & CAP_NOISE) != 0,
                "OPPO delivers ready() before confirming CAP_NOISE alongside initial onNoiseMode");
        int connIdx = ok.events.indexOf("connected:" + CAP_BATTERY);
        int modeIdx = ok.events.indexOf("mode:" + MODE_ANC + ":" + (CAP_BATTERY | CAP_NOISE));
        check(connIdx >= 0 && modeIdx > connIdx, "OPPO event sequence avoids premature ControlHub write");
    }

    private static void sonyFixes() {
        check(SonyProtocol.mode(2, hex("67 15 01 01 00 03 00 00")) == MODE_ANC,
                "Sony type 0x15 wind mode byte 3 accepted as ANC");
        check(SonyProtocol.mode(2, hex("67 15 01 01 00 05 00 00")) == MODE_ANC,
                "Sony type 0x15 wind mode byte 5 accepted as ANC");
        check(SonyProtocol.mode(2, hex("67 15 01 01 00 04 00 00")) == -1,
                "Sony type 0x15 invalid nc byte 4 still rejected");

        // TWS XM5: type=0 returning 0xFF must not flip singleBattery=true; type=14 seq=0 must not block type=12 seq=0.
        Rig xm5 = new Rig("WF-1000XM5");
        xm5.open();
        xm5.receive(SonyProtocol.encode(12, 0, hex("01 00 03 00 10 02 00 00")));
        check(xm5.connections == 1, "XM5 ready");
        // Receive DATA_MDR_NO2 (type=14, seq=1) before DATA_MDR (type=12, seq=1).
        xm5.receive(SonyProtocol.encode(14, 1, hex("00 00")));
        xm5.receive(SonyProtocol.encode(12, 1, hex("23 00 FF 00")));
        check((xm5.driver.caps() & CAP_BATTERY_SINGLE) == 0 && (xm5.driver.caps() & CAP_BATTERY) != 0,
                "Sony type=14 seq=1 does not drop type=12 seq=1, and type=0 0xFF does not flip singleBattery");

        xm5.receive(SonyProtocol.encode(12, 0, hex("23 01 50 00 4B 00")));
        check(xm5.left == 80 && xm5.right == 75 && xm5.driver.caps() == CAP_BATTERY,
                "Sony TWS battery uses CAP_BATTERY only");

        // Real single-battery headphone: type=0 valid value sets CAP_BATTERY_SINGLE (not CAP_BATTERY | CAP_BATTERY_SINGLE).
        Rig wh = new Rig("WH-1000XM5");
        wh.open();
        wh.receive(SonyProtocol.encode(12, 0, hex("01 00 03 00 10 02 00 00")));
        wh.receive(SonyProtocol.encode(12, 1, hex("23 00 55 01")));
        check(wh.left == 85 && wh.charge == 1 && wh.driver.caps() == CAP_BATTERY_SINGLE,
                "Sony single-battery device sets CAP_BATTERY_SINGLE exclusively");
        // Unavailable type=1 [FF, FF] must not undo singleBattery=true.
        wh.receive(SonyProtocol.encode(12, 0, hex("23 01 FF 00 FF 00")));
        check(wh.driver.caps() == CAP_BATTERY_SINGLE,
                "Sony unavailable type=1 does not overwrite singleBattery");
    }

    private static void edifierFixes() {
        DriverModule mod = null;
        for (DriverModule m : InstalledDrivers.ALL) if (m.matches("EDIFIER FitBuds Turbo")) mod = m;
        check(mod != null && mod.matches("EDIFIER\nFitBuds Turbo") && mod.matches("EDIFIER\r\nW820NB"),
                "Edifier matches device names containing CR/LF");

        // Probes F2 + CC first; D0 deferred by 300ms.
        Rig r = new Rig("EDIFIER NeoBuds Pro 2");
        r.open();
        check(r.frames.size() == 2 && (r.frames.get(0)[2] & 255) == 0xF2 && (r.frames.get(1)[2] & 255) == 0xCC,
                "Edifier probes F2 and CC before D0");

        // Even if D0 arrives before F2, F2 remains queryable and upgrades back to TWS CAP_BATTERY.
        r.receive(EdifierProtocol.encode(0xD0, new byte[]{50}, true, true)
                .length > 0 ? concat(hex("BB EC D0 00 01"), new byte[]{(byte) (50 ^ 0xA5)}, new byte[]{(byte) ((0xBB + 0xEC + 0xD0 + 1 + (50 ^ 0xA5)) & 0xFF)}) : new byte[0]);
        check(r.connections == 1 && r.left == 50 && (r.driver.caps() & CAP_BATTERY_SINGLE) != 0,
                "Edifier accepts D0 fallback");
        int before = r.frames.size();
        r.driver.queryBattery();
        boolean sentF2 = false;
        for (int i = before; i < r.frames.size(); i++) if ((r.frames.get(i)[2] & 255) == 0xF2) sentF2 = true;
        check(sentF2, "Edifier still queries F2 after early D0 so TWS can upgrade back");
        byte[] f2Plain = hex("03 64 5A 00 03 11");
        byte[] f2Xor = new byte[f2Plain.length];
        int f2Sum = 0xBB + 0xEC + 0xF2 + 0 + f2Plain.length;
        for (int i = 0; i < f2Plain.length; i++) {
            f2Xor[i] = (byte) (f2Plain[i] ^ 0xA5);
            f2Sum += f2Xor[i] & 0xFF;
        }
        r.receive(concat(hex("BB EC F2 00 06"), f2Xor, new byte[]{(byte) (f2Sum & 0xFF)}));
        check((r.driver.caps() & CAP_BATTERY_SINGLE) == 0 && (r.driver.caps() & CAP_BATTERY) != 0
                && r.left == 100 && r.right == 90,
                "Edifier upgrades back to TWS CAP_BATTERY when F2 arrives after D0");

        // Single-battery D0-only device still receives D0 probe after 300ms.
        Rig w860 = new Rig("EDIFIER W860NB");
        w860.open();
        w860.clock.tick(300);
        boolean sentD0 = false;
        for (byte[] f : w860.frames) if ((f[2] & 255) == 0xD0) sentD0 = true;
        check(sentD0, "Edifier probes D0 after 300ms when F2 has not responded");
    }

    private static byte[] roseV2Frame(int header, int seq, byte[] tlvs) {
        byte[] out = new byte[2 + tlvs.length + 2];
        out[0] = (byte) header;
        out[1] = (byte) seq;
        System.arraycopy(tlvs, 0, out, 2, tlvs.length);
        int sum = 0;
        for (int i = 0; i < out.length - 2; i++) sum += out[i] & 0xFF;
        out[out.length - 2] = (byte) (sum & 0xFF);
        out[out.length - 1] = (byte) 0xAA;
        return out;
    }

    private static void roseFixes() {
        // V2 0xAA as TLV type inside a valid frame, tested across all splits.
        byte[] v2WithAaType = roseV2Frame(0xDD, 0, hex("02 09 01 02 AA 01 04 0C 50 4A 64"));
        for (int split = 0; split <= v2WithAaType.length; split++) {
            RoseProtocol.Decoder dec = new RoseProtocol.Decoder(RoseProtocol.V2);
            List<RoseProtocol.Item> items = new ArrayList<>();
            items.addAll(dec.feed(Arrays.copyOf(v2WithAaType, split), split));
            items.addAll(dec.feed(Arrays.copyOfRange(v2WithAaType, split, v2WithAaType.length), v2WithAaType.length - split));
            check(items.size() == 3 && items.get(1).type == 0xAA && items.get(2).type == 0x0C,
                    "Rose V2 decodes 0xAA TLV-type collision at split " + split);
        }

        // Split resync must not discard an incomplete valid 0xDD or 0xFF header embedded after bad checksum.
        for (int hdr : new int[]{0xDD, 0xFF}) {
            byte[] valid = roseV2Frame(hdr, 0, hex("04 0C 50 4A 64 02 09 01"));
            byte[] corruptPrefix = hex("DD 00 05 01 02"); // precedes valid; checksum fails at 0x0C 0xAA? Let's make exact bad checksum ending in AA:
            // corrupt frame ending with [badSum, AA] just before valid header, OR corrupt outer containing split valid:
            byte[] badPacket = hex("DD 00 04 0C E4 63 11 02 09 01 00 AA");
            byte[] combined = concat(badPacket, valid);
            for (int split = badPacket.length + 1; split < combined.length; split++) {
                RoseProtocol.Decoder dec = new RoseProtocol.Decoder(RoseProtocol.V2);
                List<RoseProtocol.Item> items = new ArrayList<>();
                items.addAll(dec.feed(Arrays.copyOf(combined, split), split));
                items.addAll(dec.feed(Arrays.copyOfRange(combined, split, combined.length), combined.length - split));
                check(items.size() == 2 && items.get(0).type == 0x0C,
                        "Rose split resync preserves incomplete valid 0x" + Integer.toHexString(hdr) + " header at split " + split);
            }
            // Also test bad frame whose length spans across split valid header.
            byte[] badOuter = concat(hex("DD 00 06 01 02"), Arrays.copyOf(valid, 5)); // ends at valid[4] if 0xAA, or split mid-frame
            RoseProtocol.Decoder dec2 = new RoseProtocol.Decoder(RoseProtocol.V2);
            List<RoseProtocol.Item> items2 = new ArrayList<>();
            items2.addAll(dec2.feed(badOuter, badOuter.length));
            items2.addAll(dec2.feed(Arrays.copyOfRange(valid, 5, valid.length), valid.length - 5));
            check(items2.size() == 2 && items2.get(1).type == 0x09,
                    "Rose split resync recovers 0x" + Integer.toHexString(hdr) + " frame split mid-packet");
        }

        // Invalid all-0xFF battery returns null and does not trigger markConnected().
        check(RoseProtocol.battery(RoseProtocol.V2, hex("FF FF FF")) == null, "Rose V2 all-FF battery is null");
        check(RoseProtocol.battery(RoseProtocol.RS, hex("FF FF FF")) == null, "Rose RS all-FF battery is null");
        Rig rose = new Rig("CERAMICS-U");
        rose.open();
        rose.receive(roseV2Frame(0xDD, 0, hex("04 0C FF FF FF")));
        check(rose.connections == 0 && !rose.driver.isConnected(),
                "Rose invalid all-FF battery does not falsely trigger markConnected()");

        // Valid battery triggers markConnected() and resets lastQuery so immediate queryNoise() isn't swallowed.
        rose.clock.tick(50); // still < 300ms since open() at t=0
        rose.receive(roseV2Frame(0xDD, 0, hex("04 0C E4 63 FF")));
        check(rose.connections == 1, "Rose valid battery triggers markConnected()");
        int before = rose.frames.size();
        rose.driver.queryNoise();
        check(rose.frames.size() == before + 1, "Rose immediate queryNoise after markConnected is not swallowed");
        rose.driver.queryBattery();
        check(rose.frames.size() == before + 1, "Rose duplicate queryBattery in same ms is still deduplicated");
    }

    private static void technicsFixes() {
        // 5s readiness timeout.
        Rig stall = new Rig("Technics EAH-AZ80");
        stall.open();
        stall.clock.tick(4999);
        check(stall.failures == 0 && stall.connections == 0, "Technics does not fail before 5000ms");
        stall.clock.tick(1);
        check(stall.failures == 1 && stall.connections == 0, "Technics fails cleanly at 5000ms timeout");

        // Preserved levels and non-interleaving changeNoise vs queryBattery.
        Rig az = new Rig("Technics EAH-AZ80");
        az.open();
        az.clock.tick(0); // queryNoise + first battery command sent
        az.flushAll();
        az.clock.tick(120); az.flushAll();
        az.clock.tick(120); az.flushAll(); // initial battery series finished

        // Device reports ANC mode with custom ancLevel=80 (0x50), ambientLevel=40 (0x28).
        az.receive("05 5B 06 00 0A 00 00 01 50 28");
        check(az.connections == 1 && az.mode == MODE_ANC, "Technics ready with ANC");
        az.clock.tick(5000);
        check(az.failures == 0, "Technics cancels 5s readiness timeout on connect");

        int startFrames = az.frames.size();
        int[] flushedCount = {0};
        az.driver.changeNoise(MODE_TRANS, () -> flushedCount[0]++);
        az.clock.tick(0); // sends cmd 0 of MODE_TRANS (0x0068)
        // Now trigger queryBattery while changeNoise series is active.
        az.driver.queryBattery();
        az.clock.tick(0);
        check(az.frames.size() == startFrames + 1, "Technics queues queryBattery instead of interleaving with active changeNoise");
        az.flushLast(); // flushes 0x0068
        az.clock.tick(120);
        az.flushLast(); // flushes 0x0022
        az.clock.tick(120);
        az.flushLast(); // flushes 0x000B with preserved (80, 40)
        check(flushedCount[0] == 1, "Technics changeNoise flushed callback ran");

        byte[] setModeFrame = az.frames.get(startFrames + 2);
        check((setModeFrame[4] & 255) == 0x0B && (setModeFrame[6] & 255) == 2
                && (setModeFrame[7] & 255) == 80 && (setModeFrame[8] & 255) == 40,
                "Technics preserves reported ancLevel=80 and ambientLevel=40");
        for (int i = startFrames; i < startFrames + 3; i++) {
            int id = (az.frames.get(i)[4] & 255) | ((az.frames.get(i)[5] & 255) << 8);
            check(id != 0x0CD6 && id != 0x0040, "No battery frame interleaved inside changeNoise series");
        }

        // 120ms after mode change finishes, queued battery series runs cleanly.
        az.clock.tick(120);
        check(az.frames.size() == startFrames + 4 && (az.frames.get(startFrames + 3)[4] & 255) == 0xD6,
                "Queued Technics battery series runs after changeNoise completes");
        az.flushLast();
        az.clock.tick(120); az.flushLast();
        az.clock.tick(120); az.flushLast();
        az.clock.tick(200); // triggers readback queryNoise scheduled at +520ms after mode change

        // Reverse direction: queryBattery running when changeNoise is called -> remaining battery deferred until after changeNoise.
        int bStart = az.frames.size();
        az.driver.queryBattery();
        az.clock.tick(0); // sends 0x0CD6(0)
        az.driver.changeNoise(MODE_OFF, () -> { });
        az.clock.tick(0); // preempts battery series, starts MODE_OFF (0x0068)
        az.flushAll();
        az.clock.tick(120);
        // Must be MODE_OFF cmd 1 (0x000B), NOT battery 0x0CD6(1)!
        int midId = (az.frames.get(az.frames.size() - 1)[4] & 255) | ((az.frames.get(az.frames.size() - 1)[5] & 255) << 8);
        check(midId == 0x000B, "Technics battery series yields to new changeNoise without interleaving");
        byte[] offFrame = az.frames.get(az.frames.size() - 1);
        check((offFrame[7] & 255) == 80 && (offFrame[8] & 255) == 40, "Technics MODE_OFF preserves levels (80, 40)");
        az.flushAll();
        az.clock.tick(120);
        int resumedId = (az.frames.get(az.frames.size() - 1)[4] & 255) | ((az.frames.get(az.frames.size() - 1)[5] & 255) << 8);
        check(resumedId == 0x0CD6 && az.frames.size() > bStart + 3,
                "Deferred battery series restarts cleanly after changeNoise completes");
    }

    public static void main(String[] args) {
        nullSafety();
        xiaomiFixes();
        huaweiFixes();
        niceHckFixes();
        vivoFixes();
        oppoFixes();
        sonyFixes();
        edifierFixes();
        roseFixes();
        technicsFixes();
        System.out.println("PASS " + checks + " Wave 1 driver fix checks");
    }
}

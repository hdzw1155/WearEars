package com.hdz.wearcontrol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Sony MDR：单条在途指令，ACK 只确认收包，模式与扩展能力以 RET/NTFY 为准；绝不发 COMMAND_2 解绑指令。 */
final class SonyClient extends WireDriver {
    private static final UUID V1 = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba");
    private static final UUID V2 = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2");

    private static final OptionItem[] TRANS_OPTIONS = {
            new OptionItem(5, "通透 5", "Ambient 5", OptionItem.ROLE_DEFAULT),
            new OptionItem(10, "通透 10", "Ambient 10", OptionItem.ROLE_DEFAULT),
            new OptionItem(15, "通透 15", "Ambient 15", OptionItem.ROLE_DEFAULT),
            new OptionItem(20, "通透 20", "Ambient 20", OptionItem.ROLE_DEFAULT),
            new OptionItem(SonyProtocol.TRANS_CHOICE_VOICE, "人声聚焦", "Focus on Voice", OptionItem.ROLE_VOICE)
    };
    private static final OptionItem[] ANC_WIND_OPTIONS_V2 = {
            new OptionItem(2, "标准降噪", "Noise Canceling", OptionItem.ROLE_DEFAULT),
            new OptionItem(3, "抗风噪", "Wind", OptionItem.ROLE_WIND)
    };
    private static final OptionItem[] ANC_WIND_OPTIONS_V1 = {
            new OptionItem(2, "标准降噪", "Noise Canceling", OptionItem.ROLE_DEFAULT),
            new OptionItem(1, "抗风噪", "Wind", OptionItem.ROLE_WIND)
    };

    private static final FeatureDescriptor TRANS_MODE_SPEC = FeatureDescriptor.choice(
            FeatureId.TRANS_MODE, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_TRANS, false, TRANS_OPTIONS);
    private static final FeatureDescriptor ANC_WIND_SPEC_V2 = FeatureDescriptor.choice(
            FeatureId.ANC_LEVEL, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false, ANC_WIND_OPTIONS_V2);
    private static final FeatureDescriptor ANC_WIND_SPEC_V1 = FeatureDescriptor.choice(
            FeatureId.ANC_LEVEL, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false, ANC_WIND_OPTIONS_V1);
    private static final FeatureDescriptor WIND_SPEC = FeatureDescriptor.toggle(
            FeatureId.WIND_REDUCTION, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false);
    private static final FeatureDescriptor EQUALIZER_SPEC = FeatureDescriptor.choice(
            FeatureId.EQUALIZER, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            0, false, OptionItem.fromPresets(SonyProtocol.EQ_PRESETS));
    private static final FeatureDescriptor WEAR_DETECT_SPEC = FeatureDescriptor.toggle(
            FeatureId.WEAR_DETECTION, VERIF_UNTESTED, false);
    private static final FeatureDescriptor FIRMWARE_SPEC = FeatureDescriptor.readOnly(
            FeatureId.FIRMWARE_INFO, VERIF_UNTESTED, false);

    private static final class PendingWrite {
        final byte[] frame;
        final Runnable flushed;
        final Runnable readback;
        PendingWrite(byte[] frame, Runnable flushed, Runnable readback) {
            this.frame = frame;
            this.flushed = flushed;
            this.readback = readback;
        }
    }

    private SonyProtocol.Decoder decoder;
    private final ArrayDeque<byte[]> commands = new ArrayDeque<byte[]>();
    private final List<PendingWrite> pendingWrites = new ArrayList<PendingWrite>();
    private final boolean[] supportedFuncs = new boolean[256];
    private byte[] sending, noiseState, requestedFrame;
    private Runnable modeFlushed;
    private int sequence, attempt, transaction, generation, handshake;
    private boolean writePending, helloAfterWrite;
    private int lastSeqType12 = -1, lastSeqType14 = -1;
    private final int[] battery = {255, 255, 255};
    private int charging;
    private volatile boolean singleBattery, noiseConfirmed, windConfirmed, eqConfirmed, wearDetectConfirmed;
    private volatile boolean dseeSupported, speakToChatSupported, wearDetectSupported;
    private volatile int eqInquiryType = -1;
    private volatile FeatureDescriptor ancLevelSpec;
    private volatile Boolean dseeState, speakToChatState;
    private volatile String codecName = "", firmwareVersion = "";

    @Override protected UUID[] services(UUID[] advertised) {
        if (advertised != null) for (UUID service : advertised)
            if (V1.equals(service)) return new UUID[]{V1, V2};
        return new UUID[]{V2, V1};
    }
    @Override protected void opened() {
        decoder = new SonyProtocol.Decoder();
        commands.clear();
        pendingWrites.clear();
        Arrays.fill(supportedFuncs, false);
        sending = null; noiseState = null; requestedFrame = null; modeFlushed = null;
        sequence = attempt = transaction = generation = handshake = charging = 0;
        writePending = helloAfterWrite = false;
        lastSeqType12 = lastSeqType14 = -1;
        noiseConfirmed = singleBattery = windConfirmed = eqConfirmed = wearDetectConfirmed = false;
        dseeSupported = speakToChatSupported = wearDetectSupported = false;
        eqInquiryType = -1;
        ancLevelSpec = null;
        dseeState = null;
        speakToChatState = null;
        codecName = "";
        firmwareVersion = "";
        Arrays.fill(battery, 255);
        hello();
    }

    private void hello() {
        if (generation != 0) return;
        if (writePending) { helloAfterWrite = true; return; }
        if (++handshake > 3) { fail("耳机未返回 Sony MDR 协议版本"); return; }
        enqueue(new byte[]{0, 0});
        if (writePending) helloAfterWrite = true;
        else scheduleHello();
    }

    private void scheduleHello() {
        schedule(new Runnable() { @Override public void run() { hello(); }}, 1500);
    }

    private void enqueue(byte[] command) {
        if (command == null) return;
        if (Arrays.equals(sending, command)) return;
        for (byte[] queued : commands) if (Arrays.equals(queued, command)) return;
        if (commands.size() >= 24) return;
        commands.add(command);
        next();
    }
    private void next() {
        if (sending != null || commands.isEmpty()) return;
        sending = commands.remove();
        attempt = 0;
        transmit();
    }
    private void transmit() {
        if (++attempt > 3) { fail("Sony 控制指令未收到确认"); return; }
        final int id = ++transaction;
        final byte[] frame = sending;
        writePending = true;
        writeThen(SonyProtocol.encode(12, sequence, sending), new Runnable() { @Override public void run() {
            if (requestedFrame == frame && modeFlushed != null) {
                Runnable notify = modeFlushed;
                modeFlushed = null;
                notify.run();
                schedule(SonyClient.this::queryNoise, 700);
            }
            for (Iterator<PendingWrite> it = pendingWrites.iterator(); it.hasNext(); ) {
                PendingWrite pw = it.next();
                if (pw.frame == frame || Arrays.equals(pw.frame, frame)) {
                    it.remove();
                    if (pw.flushed != null) pw.flushed.run();
                    if (pw.readback != null) schedule(pw.readback, 700);
                }
            }
            if (sending != null && id == transaction) {
                writePending = false;
                schedule(new Runnable() { @Override public void run() {
                    if (sending != null && id == transaction) transmit();
                }}, 1000);
            }
            if (helloAfterWrite && !writePending) {
                helloAfterWrite = false;
                if (generation == 0) scheduleHello();
            }
        }});
    }

    private void enqueueWrite(byte[] command, int replaceOpcode, Runnable flushed, Runnable readback) {
        if (command == null || generation == 0) return;
        if (replaceOpcode >= 0) {
            boolean matchSub = replaceOpcode != 0x68 && command.length >= 2;
            Iterator<byte[]> iterator = commands.iterator();
            while (iterator.hasNext()) {
                byte[] q = iterator.next();
                if (q.length > 0 && (q[0] & 255) == replaceOpcode
                        && (!matchSub || (q.length >= 2 && q[1] == command[1]))) {
                    iterator.remove();
                    removePendingWrites(q);
                }
            }
        }
        if (Arrays.equals(sending, command) && !writePending) {
            if (flushed != null) flushed.run();
            if (readback != null) schedule(readback, 700);
            return;
        }
        boolean alreadySending = Arrays.equals(sending, command);
        boolean alreadyQueued = false;
        for (byte[] queued : commands) {
            if (Arrays.equals(queued, command)) { alreadyQueued = true; break; }
        }
        if (!alreadySending && !alreadyQueued && commands.size() >= 24) return;
        byte[] target = alreadySending ? sending : command;
        pendingWrites.add(new PendingWrite(target, flushed, readback));
        enqueue(command);
    }

    private void removePendingWrites(byte[] evicted) {
        for (Iterator<PendingWrite> it = pendingWrites.iterator(); it.hasNext(); ) {
            PendingWrite pw = it.next();
            if (pw.frame == evicted || Arrays.equals(pw.frame, evicted)) {
                it.remove();
            }
        }
    }

    private void publishInfoText() {
        StringBuilder sb = new StringBuilder();
        if (!firmwareVersion.isEmpty()) sb.append(firmwareVersion);
        if (!codecName.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(codecName);
        }
        if (Boolean.TRUE.equals(dseeState)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("DSEE");
        }
        if (Boolean.TRUE.equals(speakToChatState)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("Speak-to-Chat");
        }
        if (sb.length() > 0) {
            confirmFeature(FIRMWARE_SPEC);
            reportFeatureText(FeatureId.FIRMWARE_INFO, sb.toString());
        }
    }

    private void handleCapabilities(int[] funcs) {
        for (int func : funcs) {
            if (func >= 0 && func < supportedFuncs.length) supportedFuncs[func] = true;
        }
        if (supportedFuncs[SonyProtocol.FUNC_UPSCALING_DSEE]) {
            dseeSupported = true;
            enqueue(SonyProtocol.dseeQuery(generation));
        }
        int eqInq = SonyProtocol.eqInquiryForCapabilities(generation, supportedFuncs);
        if (eqInq >= 0) {
            eqInquiryType = eqInq;
            enqueue(SonyProtocol.eqQuery(eqInq));
        }
        if (supportedFuncs[SonyProtocol.FUNC_SPEAK_TO_CHAT_1] || supportedFuncs[SonyProtocol.FUNC_SPEAK_TO_CHAT_2]) {
            speakToChatSupported = true;
            enqueue(SonyProtocol.speakToChatQuery(generation));
        }
        if (supportedFuncs[SonyProtocol.FUNC_PAUSE_ON_REMOVE]) {
            wearDetectSupported = true;
            enqueue(SonyProtocol.wearDetectQuery(generation));
        }
    }

    @Override protected void received(byte[] bytes) {
        if (!transportOpen()) return;
        for (SonyProtocol.Frame frame : decoder.feed(bytes)) {
            if (frame.type == 1) {
                if (sending != null && frame.sequence == 1 - sequence) {
                    sequence = frame.sequence;
                    sending = null;
                    writePending = false;
                    ++transaction;
                    next();
                }
                continue;
            }
            write(SonyProtocol.encode(1, 1 - frame.sequence, new byte[0]));
            if (frame.type == 12) {
                if (lastSeqType12 == frame.sequence) continue; // 重发仍须 ACK，但不重复处理。
                lastSeqType12 = frame.sequence;
            } else if (frame.type == 14) {
                if (lastSeqType14 == frame.sequence) continue;
                lastSeqType14 = frame.sequence;
                continue;
            } else {
                continue;
            }
            byte[] p = frame.payload;
            int version = SonyProtocol.generation(p);
            if (version != 0 && generation == 0) {
                generation = version;
                ready();
                enqueue(SonyProtocol.capabilityQuery());
                queryNoise();
                queryBattery();
                enqueue(SonyProtocol.codecQuery(generation));
            }
            if (generation == 0) continue;
            int[] funcs = SonyProtocol.capabilities(p);
            if (funcs != null) {
                handleCapabilities(funcs);
                continue;
            }
            int[] value = SonyProtocol.battery(generation, p);
            if (value != null) {
                if (p[1] == 0 && value[0] >= 0 && value[0] <= 100) singleBattery = true;
                else if (p[1] == 1 && (value[0] <= 100 || value[1] <= 100)) singleBattery = false;
                for (int i = 0; i < 3; i++) if (value[i] != 255) {
                    battery[i] = value[i];
                    charging = (charging & ~(1 << i)) | (value[3] & (1 << i));
                }
                events().onBattery(battery[0], battery[1], battery[2], charging);
            }
            int mode = SonyProtocol.mode(generation, p);
            if (mode >= 0) {
                noiseState = p.clone();
                noiseConfirmed = true;
                events().onNoiseMode(mode);
                int transVal = SonyProtocol.transChoice(generation, p);
                if (transVal > 0) confirmFeature(TRANS_MODE_SPEC, transVal);
                if (SonyProtocol.supportsWind(generation, p)) {
                    FeatureDescriptor windAnc = generation == 1 ? ANC_WIND_SPEC_V1 : ANC_WIND_SPEC_V2;
                    ancLevelSpec = windAnc;
                    windConfirmed = true;
                    confirmFeature(windAnc, SonyProtocol.ancLevelValue(generation, p));
                    confirmFeature(WIND_SPEC, SonyProtocol.isWindActive(generation, p) ? 1 : 0);
                }
            }
            int[] eq = SonyProtocol.eqState(p);
            if (eq != null) {
                eqInquiryType = eq[0];
                eqConfirmed = true;
                confirmFeature(EQUALIZER_SPEC, eq[1]);
                events().onEqualizer(eq[1]);
            }
            Boolean dsee = SonyProtocol.dsee(generation, p);
            if (dsee != null) {
                dseeSupported = true;
                dseeState = dsee;
                publishInfoText();
            }
            Boolean stc = SonyProtocol.speakToChat(generation, p);
            if (stc != null) {
                speakToChatSupported = true;
                speakToChatState = stc;
                publishInfoText();
            }
            Boolean wear = SonyProtocol.wearDetect(generation, p);
            if (wear != null) {
                wearDetectSupported = true;
                wearDetectConfirmed = true;
                confirmFeature(WEAR_DETECT_SPEC, wear ? 1 : 0);
            }
            String codec = SonyProtocol.codec(generation, p);
            if (codec != null) {
                codecName = codec;
                publishInfoText();
            }
            String fw = SonyProtocol.firmware(p);
            if (fw != null) {
                firmwareVersion = fw;
                publishInfoText();
            }
        }
    }

    @Override public void queryBattery() {
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0) return;
            byte command = (byte) (generation == 1 ? 0x10 : 0x22);
            enqueue(new byte[]{command, 0});
            enqueue(new byte[]{command, 1});
            enqueue(new byte[]{command, 2});
        }}, 0);
    }
    @Override public void queryNoise() {
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0) return;
            if (noiseState != null) enqueue(new byte[]{0x66, noiseState[1]});
            else if (generation == 1) enqueue(new byte[]{0x66, 2});
            else {
                // 只读三个已知格式，设备必须返回完整合法状态才开放写入。
                enqueue(new byte[]{0x66, 0x17});
                enqueue(new byte[]{0x66, 0x19});
                enqueue(new byte[]{0x66, 0x15});
            }
        }}, 0);
    }
    @Override public void changeNoise(final int mode, Runnable flushed) {
        schedule(new Runnable() { @Override public void run() {
            byte[] command = SonyProtocol.noiseCommand(generation, noiseState, mode);
            if (command == null) return;
            Iterator<byte[]> iterator = commands.iterator();
            while (iterator.hasNext()) if (iterator.next()[0] == 0x68) iterator.remove();
            if (Arrays.equals(sending, command) && !writePending) {
                if (flushed != null) flushed.run();
                schedule(SonyClient.this::queryNoise, 700);
                return;
            }
            requestedFrame = Arrays.equals(sending, command) ? sending : command;
            modeFlushed = flushed;
            enqueue(command);
        }}, 0);
    }

    void queryDsee() {
        schedule(new Runnable() { @Override public void run() {
            if (generation != 0) enqueue(SonyProtocol.dseeQuery(generation));
        }}, 0);
    }

    void setDsee(final boolean enabled, final Runnable flushed) {
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0 || !dseeSupported) return;
            enqueueWrite(SonyProtocol.dseeCommand(generation, enabled), 0xE8, flushed, SonyClient.this::queryDsee);
        }}, 0);
    }

    Boolean dseeEnabled() { return dseeState; }

    void querySpeakToChat() {
        schedule(new Runnable() { @Override public void run() {
            if (generation != 0) enqueue(SonyProtocol.speakToChatQuery(generation));
        }}, 0);
    }

    void setSpeakToChat(final boolean enabled, final Runnable flushed) {
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0 || !speakToChatSupported) return;
            enqueueWrite(SonyProtocol.speakToChatCommand(generation, enabled), 0xF8, flushed, SonyClient.this::querySpeakToChat);
        }}, 0);
    }

    Boolean speakToChatEnabled() { return speakToChatState; }

    String activeCodec() { return codecName; }

    @Override public SoundPreset[] equalizerPresets() {
        return eqConfirmed ? SonyProtocol.EQ_PRESETS.clone() : new SoundPreset[0];
    }

    @Override public void queryEqualizer() {
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0) return;
            int inq = eqInquiryType >= 0 ? eqInquiryType : (generation == 1 ? 0x01 : 0x00);
            enqueue(SonyProtocol.eqQuery(inq));
        }}, 0);
    }

    @Override public void setEqualizer(int preset) {
        writeFeature(FeatureId.EQUALIZER, preset, null);
    }

    @Override public FeatureDescriptor[] featureDescriptors() {
        List<FeatureDescriptor> list = new ArrayList<>();
        if (ancLevelSpec != null) list.add(ancLevelSpec);
        if (noiseConfirmed) list.add(TRANS_MODE_SPEC);
        if (windConfirmed) list.add(WIND_SPEC);
        if (eqConfirmed) list.add(EQUALIZER_SPEC);
        if (wearDetectConfirmed) list.add(WEAR_DETECT_SPEC);
        if (!codecName.isEmpty() || !firmwareVersion.isEmpty()) list.add(FIRMWARE_SPEC);
        return list.toArray(new FeatureDescriptor[0]);
    }

    @Override public void queryFeature(final FeatureId id) {
        if (id == null) return;
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0) return;
            switch (id) {
                case NOISE_MODE:
                case ANC_LEVEL:
                case TRANS_MODE:
                case WIND_REDUCTION:
                    queryNoise();
                    break;
                case EQUALIZER:
                    queryEqualizer();
                    break;
                case WEAR_DETECTION:
                    if (wearDetectSupported || wearDetectConfirmed) {
                        enqueue(SonyProtocol.wearDetectQuery(generation));
                    }
                    break;
                case FIRMWARE_INFO:
                    enqueue(SonyProtocol.codecQuery(generation));
                    break;
                default:
                    break;
            }
        }}, 0);
    }

    @Override public void writeFeature(final FeatureId id, final int value, final Runnable flushed) {
        if (id == null) return;
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0) return;
            switch (id) {
                case NOISE_MODE:
                    changeNoise(value, flushed);
                    break;
                case TRANS_MODE: {
                    byte[] cmd = SonyProtocol.transChoiceCommand(generation, noiseState, value);
                    if (cmd != null) enqueueWrite(cmd, 0x68, flushed, SonyClient.this::queryNoise);
                    break;
                }
                case ANC_LEVEL: {
                    FeatureDescriptor spec = ancLevelSpec;
                    if (spec == null || !spec.isValidValue(value)) return;
                    boolean wind = generation == 1 ? value == 1 : value == 3;
                    byte[] cmd = SonyProtocol.windCommand(generation, noiseState, wind);
                    if (cmd != null) enqueueWrite(cmd, 0x68, flushed, SonyClient.this::queryNoise);
                    break;
                }
                case WIND_REDUCTION: {
                    if (!windConfirmed || (value != 0 && value != 1)) return;
                    byte[] cmd = SonyProtocol.windCommand(generation, noiseState, value != 0);
                    if (cmd != null) enqueueWrite(cmd, 0x68, flushed, SonyClient.this::queryNoise);
                    break;
                }
                case EQUALIZER: {
                    if (!eqConfirmed || eqInquiryType < 0) return;
                    byte[] cmd = SonyProtocol.eqCommand(eqInquiryType, value);
                    if (cmd != null) enqueueWrite(cmd, 0x58, flushed, SonyClient.this::queryEqualizer);
                    break;
                }
                case WEAR_DETECTION: {
                    if (!wearDetectConfirmed || (value != 0 && value != 1)) return;
                    byte[] cmd = SonyProtocol.wearDetectCommand(generation, value != 0);
                    enqueueWrite(cmd, 0xF8, flushed, () -> enqueue(SonyProtocol.wearDetectQuery(generation)));
                    break;
                }
                default:
                    break;
            }
        }}, 0);
    }

    @Override public int caps() {
        return (singleBattery ? CAP_BATTERY_SINGLE : CAP_BATTERY)
                | (noiseConfirmed ? CAP_NOISE : 0)
                | (eqConfirmed ? CAP_EQUALIZER : 0);
    }
    @Override public int verifLevel() {
        return VERIF_UNTESTED;
    }
    @Override public String brandName() { return "Sony"; }
    @Override public int potentialCaps() { return caps() | CAP_NOISE | CAP_EQUALIZER; }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            return SonyProtocol.matches(name);
        }
        @Override public WireDriver create() { return new SonyClient(); }
    }

}

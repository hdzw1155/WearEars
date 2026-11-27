package com.hdz.wearcontrol;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 原道(NiceHCK / OriG in)客户端.
 *
 * 协议依据: ZaeXT/NiceHCK_Controller(Java, MIT) + HELPMEEADICE/NiceHCK_Controller_Windows(Rust), 两边常量一致.
 * 帧: 4E | len_lo len_hi | 00 | op_lo op_hi | params...
 *   len = 3 + params 长度(u16 小端, 只算 len 之后的字节, 含 00+op); 总长 = len + 3; 无校验.
 * 扩展能力: 降噪深度/抗风噪(0x0101/0x0201)、游戏模式(0x0108/0x0208)、7档 EQ(0x0107/0x0207, 5/6 档按 0x0003 固件>=4.08 门控).
 * 严禁发送 0x0204 Codec 切换指令(会触发断连/重启).
 * 不走 BLE, 纯 SPP; 通道号不写死, 交给系统 SDP(createRfcommSocketToServiceRecord).
 */
public class NiceHckClient extends WireDriver {

    private static final String TAG = "NiceHck";

    /** 尾部 ASCII "NHCKCTRL" */
    private static final UUID SPP_UUID = UUID.fromString("0000a100-1000-8000-4e48-434b4354524c");
    private static final UUID SPP_UUID_STD = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    static final int OP_FIRMWARE = 0x0003;
    private static final int OP_BATTERY = 0x0005;
    private static final int OP_ANC_QUERY = 0x0101;
    private static final int OP_ANC_SET = 0x0201;
    static final int OP_EQ_QUERY = 0x0107;
    static final int OP_EQ_SET = 0x0207;
    static final int OP_GAME_QUERY = 0x0108;
    static final int OP_GAME_SET = 0x0208;

    private static final OptionItem[] ANC_OPTIONS = {
            new OptionItem(2, "普通降噪", "Normal", OptionItem.ROLE_DEFAULT),
            new OptionItem(3, "深度降噪", "Deep", OptionItem.ROLE_DEFAULT),
            new OptionItem(0x11, "抗风噪", "Wind", OptionItem.ROLE_WIND)
    };

    private static final SoundPreset[] EQ_PRESETS_5 = {
            new SoundPreset(0, "悔恨之泪", "OriG in"),
            new SoundPreset(1, "均衡", "Balanced"),
            new SoundPreset(2, "欧美澎湃", "Bass Surge"),
            new SoundPreset(3, "真律", "True Rhythm"),
            new SoundPreset(4, "游戏", "Game")
    };

    private static final SoundPreset[] EQ_PRESETS_7 = {
            new SoundPreset(0, "悔恨之泪", "OriG in"),
            new SoundPreset(1, "均衡", "Balanced"),
            new SoundPreset(2, "欧美澎湃", "Bass Surge"),
            new SoundPreset(3, "真律", "True Rhythm"),
            new SoundPreset(4, "游戏", "Game"),
            new SoundPreset(5, "细腻", "Delicate"),
            new SoundPreset(6, "温婉人声", "Warm Vocal")
    };

    private static final FeatureDescriptor ANC_LEVEL_SPEC = FeatureDescriptor.choice(
            FeatureId.ANC_LEVEL, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false, ANC_OPTIONS);
    private static final FeatureDescriptor LOW_LATENCY_SPEC = FeatureDescriptor.toggle(
            FeatureId.LOW_LATENCY, VERIF_UNTESTED, false);
    private static final FeatureDescriptor EQ_SPEC_5 = FeatureDescriptor.choice(
            FeatureId.EQUALIZER, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            0, false, OptionItem.fromPresets(EQ_PRESETS_5),
            "5/6 档 EQ 需固件 ≥ 4.08", "EQ presets 5–6 require FW >= 4.08");
    private static final FeatureDescriptor EQ_SPEC_7 = FeatureDescriptor.choice(
            FeatureId.EQUALIZER, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            0, false, OptionItem.fromPresets(EQ_PRESETS_7));
    private static final FeatureDescriptor FIRMWARE_SPEC = FeatureDescriptor.readOnly(
            FeatureId.FIRMWARE_INFO, VERIF_UNTESTED, false);

    private final NiceHckProtocol decoder = new NiceHckProtocol();
    private volatile boolean noiseConfirmed;
    private volatile boolean fwAtLeast408;
    private volatile boolean fwConfirmed;
    private volatile boolean lowLatencyConfirmed;
    private volatile boolean eqConfirmed;
    private volatile int lastEqValue = -1;
    private volatile int pendingEqValue = -1;
    private volatile FeatureDescriptor ancLevelSpec;
    private volatile FeatureDescriptor equalizerSpec;
    private final Runnable confirm = new Runnable() {
        @Override public void run() {
            queryNoise();
            queryBattery();
        }
    };

    @Override protected String logTag() { return TAG; }
    @Override protected String unnamedDevice() { return "原道耳机"; }
    @Override protected long failureCooldown() { return 1500; }
    @Override protected String connectionFailure() { return "连不上原道控制通道(SPP 没开?)"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{SPP_UUID, SPP_UUID_STD}; }
    @Override protected void prepare() {
        noiseConfirmed = false;
        fwAtLeast408 = false;
        fwConfirmed = false;
        lowLatencyConfirmed = false;
        eqConfirmed = false;
        lastEqValue = -1;
        pendingEqValue = -1;
        ancLevelSpec = null;
        equalizerSpec = null;
        decoder.reset();
    }
    @Override protected void opened() {
        ready();
        queryBattery();
        sendFrame(OP_FIRMWARE, new byte[0]);
        schedule(new Runnable() { @Override public void run() {
            queryNoise();
            sendFrame(OP_GAME_QUERY, new byte[0]);
            sendFrame(OP_EQ_QUERY, new byte[0]);
        }}, 120);
    }
    @Override protected void received(byte[] data) {
        if (!transportOpen() || data == null || data.length == 0) return;
        for (byte[] frame : decoder.feed(data, data.length)) onFrame(frame, 0);
    }

    private void onFrame(byte[] b, int i) {
        int flen = (b[i + 1] & 0xFF) | ((b[i + 2] & 0xFF) << 8);
        int op = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8);
        int payloadLen = flen + 3 - 6;
        if (payloadLen < 0) return;
        try {
            if (op == OP_BATTERY && payloadLen >= 3) {
                int l = b[i + 6] & 0xFF;
                int r = b[i + 7] & 0xFF;
                int c = b[i + 8] & 0xFF;
                deliverBattery(l, r, c);
            } else if (op == OP_FIRMWARE && payloadLen >= 2) {
                int sub = b[i + 6] & 0xFF;
                int main = b[i + 7] & 0xFF;
                if (main == 0 && sub == 0) return;
                fwConfirmed = true;
                fwAtLeast408 = main > 4 || (main == 4 && sub >= 8);
                String text = main + "." + (sub < 10 ? "0" + sub : String.valueOf(sub));
                confirmFeature(FIRMWARE_SPEC);
                reportFeatureText(FeatureId.FIRMWARE_INFO, text);
                if (fwAtLeast408) {
                    if (pendingEqValue >= 0) {
                        int eq = pendingEqValue;
                        pendingEqValue = -1;
                        lastEqValue = eq;
                        eqConfirmed = true;
                        equalizerSpec = EQ_SPEC_7;
                        confirmFeature(EQ_SPEC_7, eq);
                        events().onEqualizer(eq);
                    } else if (eqConfirmed && equalizerSpec != EQ_SPEC_7) {
                        equalizerSpec = EQ_SPEC_7;
                        if (lastEqValue >= 0) confirmFeature(EQ_SPEC_7, lastEqValue);
                        else confirmFeature(EQ_SPEC_7);
                    }
                }
            } else if (op == OP_ANC_QUERY && payloadLen >= 1) {
                int wire = b[i + 6] & 0xFF;
                if (wire <= 3 || wire == 0x10 || wire == 0x11) {
                    noiseConfirmed = true;
                    ancLevelSpec = ANC_LEVEL_SPEC;
                    deliverMode(mapMode(wire));
                    if (wire == 2 || wire == 3 || wire == 0x11) {
                        confirmFeature(ANC_LEVEL_SPEC, wire);
                    } else {
                        confirmFeature(ANC_LEVEL_SPEC);
                    }
                }
            } else if (op == OP_GAME_QUERY && payloadLen >= 1) {
                int val = b[i + 6] & 0xFF;
                if (val == 0 || val == 1) {
                    lowLatencyConfirmed = true;
                    confirmFeature(LOW_LATENCY_SPEC, val);
                }
            } else if (op == OP_EQ_QUERY && payloadLen >= 1) {
                int eq = b[i + 6] & 0xFF;
                if (eq >= 0 && eq <= 4) {
                    lastEqValue = eq;
                    pendingEqValue = -1;
                    eqConfirmed = true;
                    equalizerSpec = fwAtLeast408 ? EQ_SPEC_7 : EQ_SPEC_5;
                    confirmFeature(equalizerSpec, eq);
                    events().onEqualizer(eq);
                } else if (eq == 5 || eq == 6) {
                    if (fwAtLeast408) {
                        lastEqValue = eq;
                        pendingEqValue = -1;
                        eqConfirmed = true;
                        equalizerSpec = EQ_SPEC_7;
                        confirmFeature(EQ_SPEC_7, eq);
                        events().onEqualizer(eq);
                    } else if (!fwConfirmed) {
                        pendingEqValue = eq;
                    }
                }
            }
        } catch (RuntimeException e) {
            trace(TAG, "parse frame failed: " + e);
        }
    }

    /** 原道映射: 0=关 1=通透 2=普通降噪 3=深度降噪 0x10=试验性 0x11=抗风噪(归入降噪态以联动 ANC_LEVEL) */
    private static int mapMode(int v) {
        if (v == 0) return MODE_OFF;
        if (v == 1) return MODE_TRANS;
        if (v == 2 || v == 3 || v == 0x11) return MODE_ANC;
        return -1; // 试验/未知: 三态 UI 不点亮
    }

    private int toDeviceMode(int mode) {
        if (mode == MODE_OFF) return 0;
        if (mode == MODE_TRANS) return 1;
        if (mode == MODE_ANC) return 2;
        return -1;
    }

    private void sendFrame(int op, byte[] params) { write(frame(op, params)); }

    private static byte[] frame(int op, byte[] params) {
        int len = 3 + params.length;
        final byte[] f = new byte[len + 3];
        f[0] = 0x4E;
        f[1] = (byte) (len & 0xFF);
        f[2] = (byte) ((len >> 8) & 0xFF);
        f[3] = 0x00;
        f[4] = (byte) (op & 0xFF);
        f[5] = (byte) ((op >> 8) & 0xFF);
        if (params.length > 0) System.arraycopy(params, 0, f, 6, params.length);
        return f;
    }

    private static int normPct(int v) {
        return (v <= 0 || v > 100) ? 0xFF : v;
    }

    private void deliverBattery(int l, int r, int c) {
        // 0 = 未知/无, 映射为 0xFF 避免触发 0% 低电告警; 无充电位上报传 -1。
        events().onBattery(normPct(l), normPct(r), normPct(c), -1);
    }

    private void deliverMode(int mode) {
        events().onNoiseMode(mode);
    }

    @Override public void queryBattery() {
        sendFrame(OP_BATTERY, new byte[0]);
    }

    @Override public void queryNoise() {
        sendFrame(OP_ANC_QUERY, new byte[0]);
    }

    @Override public void changeNoise(int mode, Runnable flushed) {
        int v = toDeviceMode(mode);
        if (!noiseConfirmed || v < 0) return;
        writeThen(frame(OP_ANC_SET, new byte[]{(byte) v, 0x00}), () -> {
            if (flushed != null) flushed.run();
            cancel(confirm);
            schedule(confirm, 150);
        });
    }

    @Override public SoundPreset[] equalizerPresets() {
        if (!eqConfirmed) return new SoundPreset[0];
        return fwAtLeast408 ? EQ_PRESETS_7.clone() : EQ_PRESETS_5.clone();
    }

    @Override public void queryEqualizer() {
        sendFrame(OP_EQ_QUERY, new byte[0]);
    }

    @Override public void setEqualizer(int preset) {
        writeFeature(FeatureId.EQUALIZER, preset, null);
    }

    @Override public FeatureDescriptor[] featureDescriptors() {
        List<FeatureDescriptor> list = new ArrayList<>();
        if (ancLevelSpec != null) list.add(ancLevelSpec);
        if (lowLatencyConfirmed) list.add(LOW_LATENCY_SPEC);
        if (equalizerSpec != null) list.add(equalizerSpec);
        if (fwConfirmed) list.add(FIRMWARE_SPEC);
        return list.toArray(new FeatureDescriptor[0]);
    }

    @Override public void queryFeature(FeatureId id) {
        if (id == null) return;
        switch (id) {
            case NOISE_MODE:
            case ANC_LEVEL:
                queryNoise();
                break;
            case LOW_LATENCY:
                sendFrame(OP_GAME_QUERY, new byte[0]);
                break;
            case EQUALIZER:
                queryEqualizer();
                break;
            case FIRMWARE_INFO:
                sendFrame(OP_FIRMWARE, new byte[0]);
                break;
            default:
                break;
        }
    }

    @Override public void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (id == null) return;
        switch (id) {
            case NOISE_MODE:
                changeNoise(value, flushed);
                break;
            case ANC_LEVEL:
                if (noiseConfirmed && ancLevelSpec != null && ancLevelSpec.isValidValue(value)) {
                    writeThen(frame(OP_ANC_SET, new byte[]{(byte) value, 0x00}), () -> {
                        if (flushed != null) flushed.run();
                        cancel(confirm);
                        schedule(confirm, 150);
                    });
                }
                break;
            case LOW_LATENCY:
                if (lowLatencyConfirmed && (value == 0 || value == 1)) {
                    writeThen(frame(OP_GAME_SET, new byte[]{(byte) value}), () -> {
                        if (flushed != null) flushed.run();
                        schedule(() -> sendFrame(OP_GAME_QUERY, new byte[0]), 150);
                    });
                }
                break;
            case EQUALIZER:
                FeatureDescriptor spec = equalizerSpec;
                if (eqConfirmed && spec != null && spec.isValidValue(value)) {
                    writeThen(frame(OP_EQ_SET, new byte[]{(byte) value}), () -> {
                        if (flushed != null) flushed.run();
                        schedule(() -> sendFrame(OP_EQ_QUERY, new byte[0]), 150);
                    });
                }
                break;
            default:
                break;
        }
    }

    @Override public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0) | (eqConfirmed ? CAP_EQUALIZER : 0);
    }

    @Override public int verifLevel() {
        // 原道 OriG in(原点) 已在本项目真机验证(电量 + 降噪/通透/关闭三态);
        // 其他原道型号沿用同一 4E 帧协议, 但未逐型号实测, 如实标实验性。
        return isOrigin(deviceName()) ? VERIF_REAL : VERIF_UNTESTED;
    }

    private static boolean isOrigin(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_-]", "");
        return n.matches("(?:nicehck|yuandao|原道)?(?:origin|原点)(?:原道原点)?");
    }

    @Override public String brandName() {
        return "原道";
    }
    @Override public int potentialCaps() { return caps() | CAP_NOISE | CAP_EQUALIZER; }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            if (name == null) return false;
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.contains("yuandao") || n.contains("nicehck") || n.contains("原道") || isOrigin(name);
        }
        @Override public WireDriver create() { return new NiceHckClient(); }
    }

}

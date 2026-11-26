package com.hdz.wearcontrol.xiaomi;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.FeatureDescriptor;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.OptionItem;
import com.hdz.wearcontrol.SoundPreset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** RCSP 帧与两种 TLV；协议值保持原样，不把未知状态当成关闭或充电。 */
public final class XiaomiProtocol {
    public static final int EQ_NONE = 0;
    public static final int EQ_STANDARD = 1;
    public static final int EQ_BUDS4_PRO = 2;

    public static final int SPATIAL_NONE = 0;
    public static final int SPATIAL_FIXED_4F = 1;
    public static final int SPATIAL_HT_1D = 2;

    /** 按 VID:PID 或型号名门控的硬件能力画像；未知机型只开放基础电量与经确认的三态降噪。 */
    public static final class ModelCaps {
        public final String id;
        public final int profile;
        public final boolean noNoise;
        public final boolean ancLevels;
        public final boolean ancAdaptiveLevel;
        public final boolean adaptiveAncToggle;
        public final boolean transModes;
        public final int eqFamily;
        public final boolean lowLatency;
        public final boolean multipoint;
        public final boolean wearSensor;
        public final boolean findBuds;
        public final int spatialKind;

        private ModelCaps(String id, int profile, boolean noNoise,
                          boolean ancLevels, boolean ancAdaptiveLevel, boolean adaptiveAncToggle,
                          boolean transModes, int eqFamily, boolean lowLatency,
                          boolean multipoint, boolean wearSensor, boolean findBuds, int spatialKind) {
            this.id = id;
            this.profile = profile;
            this.noNoise = noNoise;
            this.ancLevels = ancLevels;
            this.ancAdaptiveLevel = ancAdaptiveLevel;
            this.adaptiveAncToggle = adaptiveAncToggle;
            this.transModes = transModes;
            this.eqFamily = eqFamily;
            this.lowLatency = lowLatency;
            this.multipoint = multipoint;
            this.wearSensor = wearSensor;
            this.findBuds = findBuds;
            this.spatialKind = spatialKind;
        }
    }

    public static final ModelCaps CAPS_UNKNOWN = new ModelCaps(
            "unknown", 0, false, false, false, false, false, EQ_NONE, false, false, false, false, SPATIAL_NONE);
    public static final ModelCaps CAPS_BUDS_6 = new ModelCaps(
            "buds6", 0, false, true, false, false, true, EQ_STANDARD, true, true, true, true, SPATIAL_FIXED_4F);
    public static final ModelCaps CAPS_BUDS_6_PRO = new ModelCaps(
            "buds6pro", 0, false, true, false, true, true, EQ_STANDARD, true, true, true, true, SPATIAL_HT_1D);
    public static final ModelCaps CAPS_BUDS_6_LITE = new ModelCaps(
            "buds6lite", 0, false, true, false, false, false, EQ_STANDARD, true, false, true, true, SPATIAL_NONE);
    public static final ModelCaps CAPS_BUDS_6_PLAY = new ModelCaps(
            "buds6play", 0, true, false, false, false, false, EQ_STANDARD, true, false, false, true, SPATIAL_NONE);
    public static final ModelCaps CAPS_BUDS_5 = new ModelCaps(
            "buds5", 1, false, true, false, false, true, EQ_STANDARD, true, true, true, true, SPATIAL_NONE);
    public static final ModelCaps CAPS_BUDS_5_PRO = new ModelCaps(
            "buds5pro", 0, false, true, false, true, true, EQ_STANDARD, true, true, true, true, SPATIAL_FIXED_4F);
    public static final ModelCaps CAPS_BUDS_4_PRO = new ModelCaps(
            "buds4pro", 0, false, true, false, true, false, EQ_BUDS4_PRO, true, true, true, true, SPATIAL_FIXED_4F);
    public static final ModelCaps CAPS_BUDS_3_PRO = new ModelCaps(
            "buds3pro", 2, false, true, true, false, false, EQ_NONE, false, true, true, true, SPATIAL_NONE);
    public static final ModelCaps CAPS_XIAOMI_BUDS_5 = new ModelCaps(
            "xiaomibuds5", 0, false, true, false, true, true, EQ_STANDARD, true, true, true, true, SPATIAL_FIXED_4F);

    public static ModelCaps capsForVidPid(int vid, int pid) {
        if (vid == 0x2717) {
            switch (pid) {
                case 0x509F:
                case 0x50A0:
                    return CAPS_BUDS_6;
                case 0x509D:
                case 0x509E:
                case 0x50AF:
                    return CAPS_BUDS_6_PRO;
                case 0x506A:
                case 0x506B:
                case 0x5075:
                    return CAPS_BUDS_5;
                default:
                    return null;
            }
        }
        if (vid == 0x5A4D) {
            switch (pid) {
                case 0xEA0E:
                case 0xEA0F:
                    return CAPS_BUDS_4_PRO;
                case 0xEA0A:
                case 0xEA0B:
                    return CAPS_BUDS_3_PRO;
                default:
                    return null;
            }
        }
        return null;
    }

    public static ModelCaps capsForName(String name) {
        if (name == null) return CAPS_UNKNOWN;
        String n = name.toLowerCase(java.util.Locale.ROOT)
                .replace("小米", "xiaomi")
                .replace("红米", "redmi")
                .replace("降噪版", "")
                .replaceAll("[^a-z0-9]", "");
        if (n.contains("buds6play") || n.contains("buds6active")) return CAPS_BUDS_6_PLAY;
        if (n.contains("buds6lite")) return CAPS_BUDS_6_LITE;
        if (n.contains("buds6pro")) return CAPS_BUDS_6_PRO;
        if (n.equals("redmibuds6") || n.equals("xiaomiredmibuds6") || n.equals("buds6")) return CAPS_BUDS_6;
        if (n.equals("redmibuds5pro") || n.equals("xiaomiredmibuds5pro") || n.equals("buds5pro")) return CAPS_BUDS_5_PRO;
        if (n.equals("redmibuds5") || n.equals("xiaomiredmibuds5")) return CAPS_BUDS_5;
        if (n.equals("xiaomibuds5")) return CAPS_XIAOMI_BUDS_5;
        if (n.equals("redmibuds4pro") || n.equals("xiaomiredmibuds4pro") || n.equals("buds4pro")) return CAPS_BUDS_4_PRO;
        if (n.equals("redmibuds3pro") || n.equals("xiaomiredmibuds3pro") || n.equals("buds3pro")) return CAPS_BUDS_3_PRO;
        return CAPS_UNKNOWN;
    }

    public static OptionItem[] ancOptions(ModelCaps caps) {
        boolean adaptive = caps != null && (caps.ancAdaptiveLevel || caps.adaptiveAncToggle);
        OptionItem[] out = new OptionItem[adaptive ? 4 : 3];
        out[0] = new OptionItem(0, "均衡", "Balanced", OptionItem.ROLE_DEFAULT);
        out[1] = new OptionItem(1, "轻度", "Light", OptionItem.ROLE_DEFAULT);
        out[2] = new OptionItem(2, "深度", "Deep", OptionItem.ROLE_DEFAULT);
        if (adaptive) out[3] = new OptionItem(3, "自适应", "Adaptive", OptionItem.ROLE_ADAPTIVE);
        return out;
    }

    public static OptionItem[] transOptions() {
        return new OptionItem[]{
                new OptionItem(0, "标准通透", "Standard", OptionItem.ROLE_DEFAULT),
                new OptionItem(1, "人声增强", "Enhance Voice", OptionItem.ROLE_VOICE),
                new OptionItem(2, "环境增强", "Enhance Ambient", OptionItem.ROLE_DEFAULT)
        };
    }

    public static SoundPreset[] eqPresets(int eqFamily) {
        if (eqFamily == EQ_BUDS4_PRO) {
            return new SoundPreset[]{
                    new SoundPreset(0x15, "均衡", "Balanced"),
                    new SoundPreset(0x01, "人声", "Voice"),
                    new SoundPreset(0x05, "低音", "Bass"),
                    new SoundPreset(0x06, "高音", "Treble")
            };
        }
        if (eqFamily == EQ_STANDARD) {
            return new SoundPreset[]{
                    new SoundPreset(0x00, "标准", "Standard"),
                    new SoundPreset(0x01, "人声", "Voice"),
                    new SoundPreset(0x05, "低音", "Bass"),
                    new SoundPreset(0x06, "高音", "Treble"),
                    new SoundPreset(0x07, "音量增强", "Volume Boost")
            };
        }
        return new SoundPreset[0];
    }

    public static OptionItem[] spatialSceneOptions() {
        return new OptionItem[]{
                new OptionItem(1, "标准", "Standard"),
                new OptionItem(2, "音乐", "Music"),
                new OptionItem(3, "视频", "Video"),
                new OptionItem(4, "游戏", "Game"),
                new OptionItem(5, "有声书", "Audiobook")
        };
    }

    public static OptionItem[] findBudsOptions() {
        return new OptionItem[]{
                new OptionItem(1, "左耳", "Left"),
                new OptionItem(2, "右耳", "Right"),
                new OptionItem(3, "双耳", "Both")
        };
    }

    public static FeatureDescriptor[] descriptors(ModelCaps caps, int firmwareVerif) {
        if (caps == null) caps = CAPS_UNKNOWN;
        List<FeatureDescriptor> list = new ArrayList<FeatureDescriptor>();
        if (!caps.noNoise && caps.ancLevels) {
            list.add(FeatureDescriptor.choice(FeatureId.ANC_LEVEL, Controls.VERIF_UNTESTED,
                    FeatureDescriptor.SAFETY_NONE, 1 << Controls.MODE_ANC, false,
                    ancOptions(caps), "仅降噪模式", "ANC mode only"));
        }
        if (!caps.noNoise && caps.transModes) {
            list.add(FeatureDescriptor.choice(FeatureId.TRANS_MODE, Controls.VERIF_UNTESTED,
                    FeatureDescriptor.SAFETY_NONE, 1 << Controls.MODE_TRANS, false,
                    transOptions(), "仅通透模式", "Transparency mode only"));
        }
        if (caps.eqFamily != EQ_NONE) {
            list.add(FeatureDescriptor.choice(FeatureId.EQUALIZER, Controls.VERIF_UNTESTED,
                    FeatureDescriptor.SAFETY_NONE, 0, false,
                    OptionItem.fromPresets(eqPresets(caps.eqFamily)), "", ""));
        }
        if (caps.lowLatency) {
            list.add(FeatureDescriptor.toggle(FeatureId.LOW_LATENCY, Controls.VERIF_UNTESTED, false));
        }
        if (caps.multipoint) {
            list.add(FeatureDescriptor.toggle(FeatureId.MULTIPOINT, Controls.VERIF_UNTESTED, false));
        }
        if (caps.wearSensor) {
            list.add(FeatureDescriptor.readOnly(FeatureId.WEAR_STATUS, Controls.VERIF_UNTESTED, false));
            list.add(FeatureDescriptor.toggle(FeatureId.WEAR_DETECTION, Controls.VERIF_UNTESTED, false));
        }
        if (caps.findBuds) {
            list.add(FeatureDescriptor.findBuds(Controls.VERIF_UNTESTED, false, findBudsOptions(), "", ""));
        }
        if (caps.spatialKind != SPATIAL_NONE) {
            list.add(FeatureDescriptor.toggle(FeatureId.SPATIAL_AUDIO, Controls.VERIF_UNTESTED,
                    FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true));
            if (caps.spatialKind == SPATIAL_HT_1D) {
                list.add(FeatureDescriptor.toggle(FeatureId.HEAD_TRACKING, Controls.VERIF_UNTESTED,
                        FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true));
                list.add(FeatureDescriptor.choice(FeatureId.SPATIAL_SCENE, Controls.VERIF_UNTESTED,
                        FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true, spatialSceneOptions(), "", ""));
            }
        }
        list.add(FeatureDescriptor.readOnly(FeatureId.FIRMWARE_INFO, firmwareVerif, false));
        return list.toArray(new FeatureDescriptor[0]);
    }

    /** 抓包确认的方言；其余型号保留原 RCSP 认证与 TARGET_INFO 写法。 */
    public static int profile(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)
                .replace("小米", "xiaomi")
                .replace("红米", "redmi")
                .replaceAll("[^a-z0-9]", "");
        if (n.equals("redmibuds5") || n.equals("xiaomiredmibuds5")) return 1;
        if (n.equals("redmibuds3pro") || n.equals("xiaomiredmibuds3pro")) return 2;
        return 0;
    }

    /** 真机实测确认的小米 / Redmi 型号；其余型号保持实验性未验证。 */
    public static boolean isVerifiedReal(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        String ascii = lower.replaceAll("[^a-z0-9]", "");
        String compact = lower.replaceAll("\\s+", "");
        return ascii.equals("redmibuds6") || ascii.equals("xiaomiredmibuds6")
                || compact.equals("红米buds6") || compact.equals("红米buds6降噪版");
    }

    public static int authApp(int profile) { return profile == 2 ? 4 : 1; }

    public static boolean isBannedOpcode(int opcode) {
        return opcode == 0x03 || opcode == 0x06 || opcode == 0x0B
                || (opcode >= 0xC1 && opcode <= 0xC4)
                || (opcode >= 0xD0 && opcode <= 0xD3)
                || (opcode >= 0xE1 && opcode <= 0xE7);
    }

    public static boolean isBannedConfigId(int cfgId) {
        return cfgId == 52 || cfgId == 75 || cfgId == 77 || cfgId == 86
                || cfgId == 119 || cfgId == 127 || cfgId == 0xEEEE;
    }

    public static byte[] configTlv(int cfgId, byte[] value) {
        if (cfgId < 0 || cfgId > 0xFFFF || isBannedConfigId(cfgId)) {
            throw new IllegalArgumentException("Prohibited RCSP config ID");
        }
        int vlen = value == null ? 0 : value.length;
        if (vlen > 253) throw new IllegalArgumentException("RCSP TLV value too long");
        byte[] out = new byte[3 + vlen];
        out[0] = (byte) (2 + vlen);
        out[1] = (byte) ((cfgId >> 8) & 0xFF);
        out[2] = (byte) (cfgId & 0xFF);
        if (vlen > 0) System.arraycopy(value, 0, out, 3, vlen);
        return out;
    }

    public static byte[] configQuery(int... cfgIds) {
        if (cfgIds == null || cfgIds.length == 0) return new byte[0];
        byte[] out = new byte[cfgIds.length * 2];
        for (int i = 0; i < cfgIds.length; i++) {
            out[i * 2] = (byte) ((cfgIds[i] >> 8) & 0xFF);
            out[i * 2 + 1] = (byte) (cfgIds[i] & 0xFF);
        }
        return out;
    }

    public static byte[] command(int opcode, int app, int serial, byte[] params) {
        if (params == null || params.length > 1015) throw new IllegalArgumentException("payload");
        byte[] body = new byte[params.length + 1];
        body[0] = (byte) serial;
        System.arraycopy(params, 0, body, 1, params.length);
        return frame(0xC0 | (app & 7), opcode, body);
    }
    public static byte[] response(int opcode, int app, int serial, int status, byte[] data) {
        if (data == null || data.length > 1014) throw new IllegalArgumentException("payload");
        byte[] body = new byte[data.length + 2];
        body[0] = (byte) status; body[1] = (byte) serial;
        System.arraycopy(data, 0, body, 2, data.length);
        return frame(app & 7, opcode, body);
    }
    private static byte[] frame(int flags, int opcode, byte[] body) {
        if (opcode < 0 || opcode > 255 || isBannedOpcode(opcode)) throw new IllegalArgumentException("Prohibited RCSP opcode");
        if (body.length > 1016) throw new IllegalArgumentException("payload");
        byte[] f = new byte[8 + body.length];
        f[0] = (byte) 0xFE; f[1] = (byte) 0xDC; f[2] = (byte) 0xBA;
        f[3] = (byte) flags; f[4] = (byte) opcode;
        f[5] = (byte) (body.length >> 8); f[6] = (byte) body.length;
        System.arraycopy(body, 0, f, 7, body.length); f[f.length - 1] = (byte) 0xEF;
        return f;
    }

    public static final class Decoder {
        private final byte[] pending = new byte[1024];
        private int size;
        public void reset() { size = 0; }
        public List<byte[]> feed(byte[] bytes, int length) {
            List<byte[]> frames = new ArrayList<byte[]>();
            if (bytes == null || length < 0 || length > bytes.length) return frames;
            for (int at = 0; at < length; at++) {
                if (size == pending.length) drop(1);
                pending[size++] = bytes[at];
                while (size > 0) {
                    if ((pending[0] & 255) != 0xFE
                            || size >= 2 && (pending[1] & 255) != 0xDC
                            || size >= 3 && (pending[2] & 255) != 0xBA) { drop(1); continue; }
                    if (size < 7) break;
                    int len = ((pending[5] & 255) << 8) | (pending[6] & 255);
                    int min = (pending[3] & 0x80) == 0 ? 2 : 1;
                    if (len < min || len > 1016) { drop(1); continue; }
                    if (size < len + 8) break;
                    if ((pending[len + 7] & 255) != 0xEF) { drop(1); continue; }
                    frames.add(Arrays.copyOf(pending, len + 8)); drop(len + 8);
                }
            }
            return frames;
        }
        private void drop(int count) {
            System.arraycopy(pending, count, pending, 0, size - count); size -= count;
        }
    }

    /** 返回左右盒电量和三个充电位；没有 type 7 或畸形 TLV 时不发布状态。 */
    public static int[] battery(byte[] data) {
        if (data == null) return null;
        int[] result = null;
        int pos = 0;
        while (pos < data.length) {
            int len = data[pos] & 255;
            if (len < 1 || pos + len + 1 > data.length) return null;
            if ((data[pos + 1] & 255) == 7) {
                if (len != 4) return null;
                result = new int[]{255, 255, 255, 0};
                for (int i = 0; i < 3; i++) {
                    int raw = data[pos + 2 + i] & 255;
                    if (raw == 255) continue;
                    if ((raw & 127) > 100) return null;
                    result[i] = raw & 127;
                    if ((raw & 128) != 0) result[3] |= 1 << i;
                }
            }
            pos += len + 1;
        }
        return result;
    }

    /** 提取 1B 类型 TLV (len|type|value) 中指定类型的原始值，遇畸形 TLV 时返回 null。 */
    public static byte[] targetTlv(byte[] data, int wantedType) {
        if (data == null) return null;
        byte[] result = null;
        int pos = 0;
        while (pos < data.length) {
            int len = data[pos] & 255;
            if (len < 1 || pos + len + 1 > data.length) return null;
            if ((data[pos + 1] & 255) == wantedType) {
                result = Arrays.copyOfRange(data, pos + 2, pos + 1 + len);
            }
            pos += len + 1;
        }
        return result;
    }

    /** 解析 0x02 TLV 0x01 固件版本（4B 版本段或 2B 4-nibble）。 */
    public static String firmware(byte[] data) {
        byte[] val = targetTlv(data, 0x01);
        if (val == null) return null;
        if (val.length == 4) {
            return (val[0] & 255) + "." + (val[1] & 255) + "." + (val[2] & 255) + "." + (val[3] & 255);
        }
        if (val.length == 2) {
            return ((val[0] >> 4) & 0xF) + "." + (val[0] & 0xF) + "."
                    + ((val[1] >> 4) & 0xF) + "." + (val[1] & 0xF);
        }
        if (val.length == 3) {
            return (val[0] & 255) + "." + (val[1] & 255) + "." + (val[2] & 255);
        }
        return null;
    }

    /** 解析 0x02 TLV 0x03：2B BE VID + 2B BE PID。 */
    public static int[] vidPid(byte[] data) {
        byte[] val = targetTlv(data, 0x03);
        if (val == null || val.length != 4) return null;
        int vid = ((val[0] & 255) << 8) | (val[1] & 255);
        int pid = ((val[2] & 255) << 8) | (val[3] & 255);
        return new int[]{vid, pid};
    }

    /** 解析 0x09/0x02/0x04 TLV 0x0A（或 0x06）佩戴检测开关，反相布尔：00=开，01=关。 */
    public static Boolean wearDetection(byte[] data) {
        byte[] val = targetTlv(data, 0x0A);
        if (val == null) val = targetTlv(data, 0x06);
        if (val == null || val.length != 1) return null;
        int raw = val[0] & 255;
        if (raw == 0) return Boolean.TRUE;
        if (raw == 1) return Boolean.FALSE;
        return null;
    }

    /** 解析 Config 0x000C 佩戴状态位掩码：bit3=左耳入耳，bit2=右耳入耳 -> 0..3。 */
    public static int wearStatus(byte[] data) {
        byte[] cfg = config(data, 0x000C);
        if (cfg == null || cfg.length < 1) return -1;
        int v = cfg[0] & 255;
        return ((v & 8) != 0 ? 1 : 0) | ((v & 4) != 0 ? 2 : 0);
    }

    public static byte[] config(byte[] data, int wanted) {
        if (data == null) return null;
        byte[] result = null;
        int pos = 0;
        while (pos < data.length) {
            if (pos + 3 > data.length) return null;
            int len = data[pos] & 255;
            int type = ((data[pos + 1] & 255) << 8) | (data[pos + 2] & 255);
            int start = pos + 3, end = pos + 1 + len;
            if (len == 255 && type == 52) {
                if (pos + 5 > data.length) return null;
                start = pos + 5;
                end = start + ((data[pos + 3] & 255) << 8) + (data[pos + 4] & 255);
            } else if (len < 2) return null;
            if (end > data.length) return null;
            if (type == wanted) result = Arrays.copyOfRange(data, start, end);
            pos = end;
        }
        return result;
    }
    public static int noise(byte[] data) {
        byte[] value = config(data, 11);
        return value != null && value.length == 2 && (value[0] & 255) <= 2 ? value[0] & 255 : -1;
    }
    public static boolean authMatches(byte[] challenge, byte[] data) {
        if (challenge == null || challenge.length != 16 || data == null || data.length != 17 || data[0] != 1) return false;
        byte[] expected = XiaomiAuth.e21(challenge);
        int diff = 0;
        for (int i = 0; i < 16; i++) diff |= expected[i] ^ data[i + 1];
        return diff == 0;
    }
    private XiaomiProtocol() { }
}

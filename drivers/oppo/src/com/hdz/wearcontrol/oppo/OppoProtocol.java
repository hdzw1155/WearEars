package com.hdz.wearcontrol.oppo;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.OptionItem;
import com.hdz.wearcontrol.SoundPreset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** OPOv1 长度帧与载荷编解码。所有字段边界均以当前帧为准，不能越过帧尾读取。 */
public final class OppoProtocol {
    private OppoProtocol() { }

    public static final int CMD_HELLO = 0x0100;
    public static final int CMD_PRODUCT = 0x0103;
    public static final int CMD_FIRMWARE = 0x0105;
    public static final int CMD_BATTERY_QUERY = 0x0106;
    public static final int CMD_0108 = 0x0108;
    public static final int CMD_WEAR_QUERY = 0x0109;
    public static final int CMD_ANC_QUERY = 0x010C;
    public static final int CMD_FEATURE_QUERY = 0x010D;
    public static final int CMD_EQ_QUERY = 0x010F;
    public static final int CMD_SPATIAL_QUERY = 0x012A;
    public static final int CMD_CAPS = 0x0200;
    public static final int CMD_EVENT = 0x0204;
    public static final int CMD_SUBSCRIBE = 0x0205;

    public static final int CMD_FIND_BUDS = 0x0400;
    public static final int CMD_FEATURE_SET = 0x0403;
    public static final int CMD_ANC_SET = 0x0404;
    public static final int CMD_EQ_SET = 0x0406;
    public static final int CMD_SPATIAL_43_SET = 0x041E;
    public static final int CMD_SPATIAL_47_SET = 0x0422;

    public static final int CMD_FEATURE_NOTIFY = 0x0503;
    public static final int CMD_EQ_NOTIFY = 0x0504;
    public static final int CMD_SPATIAL_NOTIFY = 0x0510;

    public static final int RSP_HELLO = 0x8100;
    public static final int RSP_PRODUCT = 0x8103;
    public static final int RSP_FIRMWARE = 0x8105;
    public static final int RSP_BATTERY = 0x8106;
    public static final int RSP_WEAR = 0x8109;
    public static final int RSP_ANC = 0x810C;
    public static final int RSP_FEATURE = 0x810D;
    public static final int RSP_EQ = 0x810F;
    public static final int RSP_SPATIAL = 0x812A;
    public static final int RSP_FIND_BUDS_ACK = 0x8400;
    public static final int RSP_FEATURE_ACK = 0x8403;
    public static final int RSP_ANC_ACK = 0x8404;

    public static final int PID_ENCO_AIR5_PRO = 0x06C410;
    public static final int PID_ENCO_FREE4 = 0x068C10;
    public static final int PID_ENCO_X2 = 0x063C10;
    public static final int PID_ENCO_X3 = 0x067410;
    public static final int PID_OP_BUDS_PRO_2 = 0x062014;
    public static final int PID_OP_BUDS_PRO_3 = 0x064014;
    public static final int PID_OP_BUDS_4 = 0x065414;
    public static final int PID_REALME_AIR7_PRO = 0x064C12;

    public static final int CAP_GRP_FIND_BUDS = 5;
    public static final int CAP_GRP_FEATURE_SWITCH = 7;
    public static final int CAP_GRP_ANC = 8;
    public static final int CAP_GRP_EQ = 10;
    public static final int CAP_GRP_SPATIAL_43 = 43;
    public static final int CAP_GRP_SPATIAL_47 = 47;

    public static final int FEAT_WEAR_DETECTION = 0x04;
    public static final int FEAT_LOW_LATENCY = 0x06;
    public static final int FEAT_MULTIPOINT = 0x11;
    public static final int FEAT_WIND_REDUCTION = 0x1A;
    public static final int FEAT_SPATIAL_AUDIO = 0x1B;

    /** 真机实测确认的 OPPO 型号；其余 OPOv1 型号保持实验性未验证。 */
    public static boolean isVerifiedReal(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return n.equals("oppoencoair5pro") || n.equals("encoair5pro")
                || n.equals("oppoencofree4") || n.equals("encofree4");
    }

    public static boolean isBannedOpcode(int cmd) {
        return cmd == 0x0F00 || (cmd >= 0xEF01 && cmd <= 0xEF09) || cmd == 0x0405 || cmd == 0x040B;
    }

    /** 组帧: AA TotalLen(LEB128) 00 00 cmd(2B LE) seq payLen(2B LE) payload。禁止编码危险/禁用操作码。 */
    public static byte[] encode(int cmd, int seqNo, byte[] payload) {
        if (cmd < 0 || cmd > 0xFFFF || isBannedOpcode(cmd)) {
            throw new IllegalArgumentException("Prohibited OPOv1 opcode: 0x" + Integer.toHexString(cmd));
        }
        int payLen = payload == null ? 0 : payload.length;
        if (payLen > 0xFFFF) {
            throw new IllegalArgumentException("OPOv1 payload too large: " + payLen);
        }
        int totalLen = 7 + payLen;
        int lebLen = 0;
        int tmp = totalLen;
        do {
            lebLen++;
            tmp >>>= 7;
        } while (tmp != 0);
        byte[] f = new byte[1 + lebLen + 7 + payLen];
        int p = 0;
        f[p++] = (byte) 0xAA;
        int rem = totalLen;
        do {
            int b = rem & 0x7F;
            rem >>>= 7;
            if (rem != 0) b |= 0x80;
            f[p++] = (byte) b;
        } while (rem != 0);
        f[p++] = 0x00;
        f[p++] = 0x00;
        f[p++] = (byte) (cmd & 0xFF);
        f[p++] = (byte) ((cmd >> 8) & 0xFF);
        f[p++] = (byte) (seqNo & 0xFF);
        f[p++] = (byte) (payLen & 0xFF);
        f[p++] = (byte) ((payLen >> 8) & 0xFF);
        if (payLen > 0) System.arraycopy(payload, 0, f, p, payLen);
        return f;
    }

    public static final class Frame {
        public final int command, sequence;
        public final byte[] payload;
        Frame(int command, int sequence, byte[] payload) {
            this.command = command; this.sequence = sequence; this.payload = payload;
        }
    }

    public static final class Decoder {
        private final byte[] buffer = new byte[4096];
        private int size;
        public synchronized List<Frame> feed(byte[] bytes, int length) {
            List<Frame> result = new ArrayList<Frame>();
            if (bytes == null || length < 0 || length > bytes.length) return result;
            for (int i = 0; i < length; i++) {
                if (size == buffer.length) drop(1);
                buffer[size++] = bytes[i];
                parse(result);
            }
            return result;
        }
        private void parse(List<Frame> result) {
            while (size > 0) {
                if ((buffer[0] & 255) != 0xAA) { drop(1); continue; }
                int p = 1, length = 0, shift = 0;
                boolean complete = false;
                while (p < size && p <= 3) {
                    int value = buffer[p++] & 255;
                    length |= (value & 127) << shift;
                    shift += 7;
                    if ((value & 128) == 0) { complete = true; break; }
                }
                if (!complete) {
                    if (p > 3) { drop(1); continue; }
                    return;
                }
                int total = p + length;
                if (length < 7 || total > buffer.length) { drop(1); continue; }
                if (size >= p + 2 && (buffer[p] != 0 || buffer[p + 1] != 0)) { drop(1); continue; }
                if (size < p + 7) return;
                int payloadLength = (buffer[p + 5] & 255) | ((buffer[p + 6] & 255) << 8);
                if (payloadLength != length - 7) { drop(1); continue; }
                if (size < total) return;
                if (buffer[p] == 0 && buffer[p + 1] == 0 && payloadLength == length - 7) {
                    int command = (buffer[p + 2] & 255) | ((buffer[p + 3] & 255) << 8);
                    result.add(new Frame(command, buffer[p + 4] & 255, Arrays.copyOfRange(buffer, p + 7, total)));
                }
                drop(total);
            }
        }
        private void drop(int count) {
            System.arraycopy(buffer, count, buffer, 0, size - count);
            size -= count;
        }
    }

    public static int[] battery(byte[] payload, boolean event) {
        if (payload == null || payload.length < 2 || payload[0] != (event ? 1 : 0)) return null;
        int count = payload[1] & 255;
        if (count < 1 || count > 3 || payload.length != 2 + count * 2) return null;
        int[] result = {255, 255, 255, 0};
        int seen = 0;
        for (int i = 2; i < payload.length; i += 2) {
            int part = (payload[i] & 255) - 1;
            if (part < 0 || part > 2 || (seen & (1 << part)) != 0) return null;
            seen |= 1 << part;
            int raw = payload[i + 1] & 255;
            if (raw == 255) continue;
            int level = raw & 127;
            if (level > 100) return null;
            result[part] = level;
            if ((raw & 128) != 0) result[3] |= 1 << part;
        }
        return result;
    }

    public static int noiseMode(int mask) {
        boolean off = (mask & 0x0008) != 0;
        boolean anc = (mask & 0x08F2) != 0; // 旧固件 0x02；Enco Air3 Pro 0x10；多档降噪 0x20/0x40/0x80/0x0800。
        boolean trans = (mask & 0x0300) != 0;
        if ((off ? 1 : 0) + (anc ? 1 : 0) + (trans ? 1 : 0) != 1) return -1;
        return off ? com.hdz.wearcontrol.Controls.MODE_OFF
                : anc ? com.hdz.wearcontrol.Controls.MODE_ANC : com.hdz.wearcontrol.Controls.MODE_TRANS;
    }

    public static int noiseMask(byte[] payload) {
        if (payload == null) return -1;
        // 旧固件 01 01 maskLE，新固件前置成功状态 00；不从任意尾部猜档位。
        int offset;
        if (payload.length == 4) offset = 0;
        else if (payload.length == 5 && payload[0] == 0) offset = 1;
        else return -1;
        if (payload[offset] != 1 || payload[offset + 1] != 1) return -1;
        return (payload[offset + 2] & 255) | ((payload[offset + 3] & 255) << 8);
    }

    /** 解析 0x8100 能力位图 ([00][bitmap LE...])；失败或状态非 0 返回 null。 */
    public static byte[] capBitmap(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        return Arrays.copyOfRange(payload, 1, payload.length);
    }

    public static boolean hasCapGroup(byte[] bitmap, int group) {
        if (bitmap == null || group < 0) return false;
        int byteIndex = group >>> 3;
        return byteIndex < bitmap.length && (bitmap[byteIndex] & (1 << (group & 7))) != 0;
    }

    /** 解析 0x8103 产品 ID ([00][3B LE PID]，同时兼容 06 xx 10/12/14 原始字节序)；失败返回 -1。 */
    public static int productId(byte[] payload) {
        if (payload == null || payload.length < 4 || payload[0] != 0) return -1;
        int b1 = payload[1] & 0xFF;
        int b2 = payload[2] & 0xFF;
        int b3 = payload[3] & 0xFF;
        if (b1 == 0x06 && b3 != 0x06) {
            return (b1 << 16) | (b2 << 8) | b3;
        }
        return b1 | (b2 << 8) | (b3 << 16);
    }

    /** 解析 0x8105 固件版本字符串 ([00][ASCII/可选长度前缀]，去尾部 NUL)。 */
    public static String firmware(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return "";
        int end = payload.length;
        while (end > 1 && payload[end - 1] == 0) end--;
        if (end <= 1) return "";
        int start = 1;
        int first = payload[1] & 0xFF;
        if (end > 2 && first > 0 && first < 0x20 && first == end - 2) {
            start = 2;
        }
        for (int i = start; i < end; i++) {
            int c = payload[i] & 0xFF;
            if (c < 0x20 || c > 0x7E) return "";
        }
        return new String(payload, start, end - start, StandardCharsets.US_ASCII).trim();
    }

    /** 解析 0x810D 批量特性开关 ([00][count][id, val]...)。 */
    public static int[][] featureSwitches(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int count = payload[1] & 0xFF;
        if (payload.length != 2 + count * 2) return null;
        int[][] result = new int[count][2];
        for (int i = 0; i < count; i++) {
            result[i][0] = payload[2 + i * 2] & 0xFF;
            result[i][1] = (payload[3 + i * 2] & 0xFF) != 0 ? 1 : 0;
        }
        return result;
    }

    /** 解析 0x8109 / 0x0204 ev 0x02 佩戴状态，返回 {leftInEar, rightInEar} (-1=未包含, 0=未佩戴, 1=入耳)。 */
    public static int[] wearFlags(byte[] payload, boolean event) {
        if (payload == null || payload.length < 2 || (payload[0] & 0xFF) != (event ? 0x02 : 0x00)) {
            return null;
        }
        int count = payload[1] & 0xFF;
        if (count < 1 || count > 4 || payload.length != 2 + count * 2) return null;
        int left = -1;
        int right = -1;
        for (int i = 0; i < count; i++) {
            int comp = payload[2 + i * 2] & 0xFF;
            int flags = payload[3 + i * 2] & 0xFF;
            int inEar = (flags & 0x02) != 0 ? 1 : 0;
            if (comp == 1) left = inEar;
            else if (comp == 2) right = inEar;
        }
        if (left < 0 && right < 0) return null;
        return new int[]{left, right};
    }

    /** 解析完整佩戴状态位掩码: 0=未佩戴, 1=左耳, 2=右耳, 3=双耳, -1=无效/单耳未知。 */
    public static int wearStatus(byte[] payload, boolean event) {
        int[] flags = wearFlags(payload, event);
        if (flags == null) return -1;
        int mask = (flags[0] == 1 ? 1 : 0) | (flags[1] == 1 ? 2 : 0);
        if (mask == 0 && (flags[0] < 0 || flags[1] < 0)) return -1;
        return mask;
    }

    /** 统一 ANC 子档位掩码家族 (显式排除 realme 064C12 与 Enco Air2 Pro 等冲突机型)。 */
    public static boolean supportsUnifiedAnc(int pid) {
        return pid == PID_ENCO_AIR5_PRO
                || pid == PID_ENCO_FREE4
                || pid == PID_ENCO_X2
                || pid == PID_ENCO_X3
                || pid == PID_OP_BUDS_PRO_2
                || pid == PID_OP_BUDS_PRO_3
                || pid == PID_OP_BUDS_4;
    }

    public static OptionItem[] ancLevelOptions(int pid) {
        if (!supportsUnifiedAnc(pid)) return new OptionItem[0];
        boolean hasAdaptive = pid == PID_ENCO_FREE4 || pid == PID_OP_BUDS_4;
        OptionItem[] base = new OptionItem[hasAdaptive ? 5 : 4];
        base[0] = new OptionItem(0x40, "轻度", "Mild", OptionItem.ROLE_DEFAULT);
        base[1] = new OptionItem(0x20, "中度", "Moderate", OptionItem.ROLE_DEFAULT);
        base[2] = new OptionItem(0x10, "深度", "Max", OptionItem.ROLE_DEFAULT);
        base[3] = new OptionItem(0x80, "智慧动态", "Smart", OptionItem.ROLE_ADAPTIVE);
        if (hasAdaptive) {
            base[4] = new OptionItem(0x0800, "自适应", "Adaptive", OptionItem.ROLE_ADAPTIVE);
        }
        return base;
    }

    public static OptionItem[] transModeOptions(int pid) {
        if (pid != PID_ENCO_X2 && pid != PID_ENCO_X3) return new OptionItem[0];
        return new OptionItem[]{
                new OptionItem(0x0100, "标准", "Standard", OptionItem.ROLE_DEFAULT),
                new OptionItem(0x0200, "人声增强", "Vocal", OptionItem.ROLE_VOICE)
        };
    }

    public static byte[] ancSetPayload(int mask) {
        if ((mask & ~0xFF) == 0) {
            return new byte[]{0x01, 0x01, (byte) (mask & 0xFF)};
        }
        return new byte[]{0x01, 0x01, (byte) (mask & 0xFF), (byte) ((mask >>> 8) & 0xFF)};
    }

    public static SoundPreset[] equalizerPresets(int pid) {
        if (pid == PID_ENCO_AIR5_PRO || pid == PID_ENCO_FREE4 || pid == PID_ENCO_X2) {
            return new SoundPreset[]{
                    new SoundPreset(0, "纯净原音", "Balanced"),
                    new SoundPreset(1, "脉冲低音", "Bass"),
                    new SoundPreset(2, "悠扬人声", "Vocals")
            };
        }
        if (pid == PID_OP_BUDS_PRO_2) {
            return new SoundPreset[]{
                    new SoundPreset(0, "均衡", "Balanced"),
                    new SoundPreset(1, "明快", "Bold"),
                    new SoundPreset(2, "清丽人声", "Serenade"),
                    new SoundPreset(3, "澎湃低音", "Bass")
            };
        }
        if (pid == PID_ENCO_X3 || pid == PID_OP_BUDS_PRO_3) {
            return new SoundPreset[]{
                    new SoundPreset(0, "纯净原音", "Balanced"),
                    new SoundPreset(1, "脉冲低音", "Bass"),
                    new SoundPreset(2, "悠扬人声", "Vocals"),
                    new SoundPreset(3, "清晰人声", "Clear"),
                    new SoundPreset(7, "丹拿特调", "Dynaudio")
            };
        }
        return new SoundPreset[0];
    }

    /** 解析单字节查询/推送值: 查询要求 [00, val]，推送允许 [val] 或 [00, val]。 */
    public static int singleStatusValue(byte[] payload, boolean notify) {
        if (payload == null) return -1;
        if (notify) {
            if (payload.length == 1) return payload[0] & 0xFF;
            if (payload.length == 2 && payload[0] == 0) return payload[1] & 0xFF;
            return -1;
        }
        if (payload.length >= 2 && payload[0] == 0) return payload[1] & 0xFF;
        return -1;
    }

    /** 解析 0x0503 特性推送 ([id, val] 或 [00, id, val]) -> {id, 0|1}。 */
    public static int[] featureNotify(byte[] payload) {
        if (payload == null) return null;
        if (payload.length == 2) {
            return new int[]{payload[0] & 0xFF, (payload[1] & 0xFF) != 0 ? 1 : 0};
        }
        if (payload.length == 3 && payload[0] == 0) {
            return new int[]{payload[1] & 0xFF, (payload[2] & 0xFF) != 0 ? 1 : 0};
        }
        return null;
    }

    /** 解析 0x0204 ev 0x05 游戏低延迟推送 ([05, val] 或 [05, 06, val]) -> 0|1, 无效返回 -1。 */
    public static int lowLatencyEvent(byte[] payload) {
        if (payload == null || payload.length < 2 || (payload[0] & 0xFF) != 0x05) return -1;
        if (payload.length == 2 && (payload[1] == 0 || payload[1] == 1)) {
            return payload[1] & 0xFF;
        }
        if (payload.length == 3 && (payload[1] & 0xFF) == FEAT_LOW_LATENCY
                && (payload[2] == 0 || payload[2] == 1)) {
            return payload[2] & 0xFF;
        }
        return -1;
    }
}

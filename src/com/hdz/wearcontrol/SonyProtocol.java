package com.hdz.wearcontrol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Sony MDR V1/V2 的帧与已确认三态格式，未知子类型只读不写。 */
final class SonyProtocol {
    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static boolean matches(String name) {
        String n = normalizeName(name);
        return n.matches("(?:le)?(?:sony)?(wh1000xm[3456]|wf1000xm[3456]|whch720n|wfc700n|wfls900n).*");
    }

    /** 真机实测确认的 Sony 型号；其余候选型号继续标注实验性未验证。 */
    static boolean isVerifiedReal(String name) {
        return normalizeName(name).matches("(?:le)?(?:sony)?wf1000xm5.*");
    }

    static byte[] encode(int type, int sequence, byte[] payload) {
        if (payload.length > 1024 || sequence < 0 || sequence > 1) throw new IllegalArgumentException("MDR frame");
        byte[] body = new byte[payload.length + 7];
        body[0] = (byte) type; body[1] = (byte) sequence;
        body[4] = (byte) (payload.length >> 8); body[5] = (byte) payload.length;
        System.arraycopy(payload, 0, body, 6, payload.length);
        int sum = 0;
        for (int i = 0; i < body.length - 1; i++) sum += body[i] & 255;
        body[body.length - 1] = (byte) sum;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x3E);
        for (byte value : body) {
            int b = value & 255;
            if (b >= 0x3C && b <= 0x3E) { output.write(0x3D); output.write(b & 0xEF); }
            else output.write(b);
        }
        output.write(0x3C);
        return output.toByteArray();
    }

    static final class Frame {
        final int type, sequence;
        final byte[] payload;
        Frame(int type, int sequence, byte[] payload) {
            this.type = type; this.sequence = sequence; this.payload = payload;
        }
    }

    static final class Decoder {
        private final byte[] buffer = new byte[1031];
        private int size;
        private boolean inside, escaped;
        List<Frame> feed(byte[] bytes) {
            List<Frame> result = new ArrayList<Frame>();
            for (byte value : bytes) {
                int b = value & 255;
                if (b == 0x3E) { inside = true; escaped = false; size = 0; continue; }
                if (!inside) continue;
                if (b == 0x3C) {
                    if (!escaped && size >= 7 && buffer[2] == 0 && buffer[3] == 0) {
                        int length = ((buffer[4] & 255) << 8) | (buffer[5] & 255);
                        int check = 0;
                        for (int i = 0; i < size - 1; i++) check += buffer[i] & 255;
                        int type = buffer[0] & 255, seq = buffer[1] & 255;
                        if (length == size - 7 && seq <= 1 && (check & 255) == (buffer[size - 1] & 255)
                                && (type == 1 || type == 12 || type == 14) && (type != 1 || length == 0)) {
                            result.add(new Frame(type, seq, Arrays.copyOfRange(buffer, 6, size - 1)));
                        }
                    }
                    inside = false;
                    continue;
                }
                if (escaped) {
                    if (b < 0x2C || b > 0x2E) { inside = false; continue; }
                    b |= 0x10; escaped = false;
                } else if (b == 0x3D) { escaped = true; continue; }
                if (size == buffer.length) { inside = false; continue; }
                buffer[size++] = (byte) b;
            }
            return result;
        }
    }

    static int generation(byte[] payload) {
        if (payload.length < 2 || payload[0] != 1 || payload[1] != 0) return 0;
        return payload.length == 4 ? 1 : payload.length == 8 ? 2 : 0;
    }

    /** 已验证的字段：左/右/盒、充电位；单电池也放在左槽，由能力位决定显示方式。 */
    static int[] battery(int generation, byte[] p) {
        if (p.length < 2) return null;
        int command = p[0] & 255;
        if (generation == 1 ? command != 0x11 && command != 0x13 : command != 0x23 && command != 0x25) return null;
        int type = p[1] & 255;
        if (type > 2 || p.length != (type == 1 ? 6 : 4)) return null;
        int[] result = {255, 255, 255, 0};
        for (int i = 0; i < (type == 1 ? 2 : 1); i++) {
            int level = p[2 + i * 2] & 255, charging = p[3 + i * 2] & 255;
            if (level == 255) continue;
            if (level > 100 || charging > (generation == 1 ? 1 : 3)) return null;
            int slot = type == 2 ? 2 : i;
            result[slot] = level;
            if (charging == 1) result[3] |= 1 << slot;
        }
        return result;
    }

    static int mode(int generation, byte[] p) {
        if (p.length < 2 || (p[0] != 0x67 && p[0] != 0x69)) return -1;
        if (generation == 1) {
            if (p.length != 8 || p[1] != 2 || (p[2] & 255) > 1 || (p[3] & 255) > 2
                    || (p[5] & 255) > 1 || (p[6] & 255) > 1 || (p[7] & 255) > 20) return -1;
            if ((p[4] & 255) > (p[3] == 2 ? 2 : 1)) return -1;
            if (p[2] == 0) return TwsClient.MODE_OFF;
            if (p[4] != 0) return TwsClient.MODE_ANC;
            return p[7] != 0 ? TwsClient.MODE_TRANS : TwsClient.MODE_OFF;
        }
        int type = p[1] & 255;
        int length = type == 0x17 ? 7 : type == 0x15 ? 8 : type == 0x19 ? 9 : 0;
        if (length == 0 || p.length != length || (p[2] & 255) > 1 || (p[3] & 255) > 1
                || (p[4] & 255) > 1) return -1;
        int focus = type == 0x15 ? 6 : 5;
        if ((p[focus] & 255) > 1 || (p[focus + 1] & 255) > 20) return -1;
        if (type == 0x15 && (p[5] & 255) > 2) return -1;
        if (type == 0x19 && ((p[7] & 255) > 1 || (p[8] & 255) > 2)) return -1;
        return p[3] == 0 ? TwsClient.MODE_OFF : p[4] == 0 ? TwsClient.MODE_ANC : TwsClient.MODE_TRANS;
    }

    /** 保留已回报的通透等级、语音及附加字段，只改三态所需字段。 */
    static byte[] noiseCommand(int generation, byte[] confirmed, int target) {
        if (confirmed == null || mode(generation, confirmed) < 0 || target < 0 || target > 2) return null;
        byte[] p = confirmed.clone();
        p[0] = 0x68;
        if (generation == 1) {
            p[2] = (byte) (target == TwsClient.MODE_OFF ? 0 : 1);
            p[4] = (byte) (target == TwsClient.MODE_ANC ? (p[3] == 2 ? 2 : 1) : 0);
            p[7] = (byte) (target == TwsClient.MODE_TRANS ? (p[5] == 0 ? 1 : Math.max(1, p[7] & 255)) : 0);
        } else {
            p[2] = 1;
            p[3] = (byte) (target == TwsClient.MODE_OFF ? 0 : 1);
            p[4] = (byte) (target == TwsClient.MODE_TRANS ? 1 : 0);
        }
        return p;
    }
}

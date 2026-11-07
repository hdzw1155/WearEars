package com.hdz.wearcontrol.xiaomi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** RCSP 帧与两种 TLV；协议值保持原样，不把未知状态当成关闭或充电。 */
public final class XiaomiProtocol {
    /** 抓包确认的方言；其余型号保留原 RCSP 认证与 TARGET_INFO 写法。 */
    public static int profile(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.equals("redmibuds5") || n.equals("xiaomiredmibuds5")) return 1;
        if (n.equals("redmibuds3pro") || n.equals("xiaomiredmibuds3pro")) return 2;
        return 0;
    }

    public static int authApp(int profile) { return profile == 2 ? 4 : 1; }

    public static byte[] command(int opcode, int app, int serial, byte[] params) {
        byte[] body = new byte[params.length + 1];
        body[0] = (byte) serial;
        System.arraycopy(params, 0, body, 1, params.length);
        return frame(0xC0 | (app & 7), opcode, body);
    }
    public static byte[] response(int opcode, int app, int serial, int status, byte[] data) {
        byte[] body = new byte[data.length + 2];
        body[0] = (byte) status; body[1] = (byte) serial;
        System.arraycopy(data, 0, body, 2, data.length);
        return frame(app & 7, opcode, body);
    }
    private static byte[] frame(int flags, int opcode, byte[] body) {
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

    public static byte[] config(byte[] data, int wanted) {
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

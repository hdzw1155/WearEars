package com.hdz.wearcontrol.oppo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** OPOv1 长度帧。所有字段边界均以当前帧为准，不能越过帧尾读取。 */
public final class OppoProtocol {
    private OppoProtocol() { }

    /** 真机实测确认的 OPPO 型号；其余 OPOv1 型号保持实验性未验证。 */
    public static boolean isVerifiedReal(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return n.equals("oppoencoair5pro") || n.equals("encoair5pro")
                || n.equals("oppoencofree4") || n.equals("encofree4");
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
                if (size < total) return;
                int payloadLength = (buffer[p + 5] & 255) | ((buffer[p + 6] & 255) << 8);
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
        if (payload.length < 2 || payload[0] != (event ? 1 : 0)) return null;
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
        boolean anc = (mask & 0x0012) != 0; // 旧固件 0x02；Enco Air3 Pro 抓包 0x10。
        boolean trans = (mask & 0x0100) != 0;
        if ((off ? 1 : 0) + (anc ? 1 : 0) + (trans ? 1 : 0) != 1) return -1;
        return off ? com.hdz.wearcontrol.TwsClient.MODE_OFF
                : anc ? com.hdz.wearcontrol.TwsClient.MODE_ANC : com.hdz.wearcontrol.TwsClient.MODE_TRANS;
    }

    public static int noiseMask(byte[] payload) {
        // 旧固件 01 01 maskLE，新固件前置成功状态 00；不从任意尾部猜档位。
        int offset;
        if (payload.length == 4) offset = 0;
        else if (payload.length == 5 && payload[0] == 0) offset = 1;
        else return -1;
        if (payload[offset] != 1 || payload[offset + 1] != 1) return -1;
        return (payload[offset + 2] & 255) | ((payload[offset + 3] & 255) << 8);
    }
}

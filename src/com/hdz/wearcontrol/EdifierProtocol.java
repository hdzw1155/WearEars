package com.hdz.wearcontrol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** BES 线格式与字段校验；不依赖 Android，使用公开报文做回归。 */
final class EdifierProtocol {
    static byte[] encode(int command, byte[] data, boolean v2, boolean xor) {
        int count = data.length;
        if (count > (v2 ? 512 : 63)) throw new IllegalArgumentException("payload");
        byte[] frame = new byte[count + (v2 ? 6 : 5)];
        frame[0] = (byte) 0xAA;
        if (v2) {
            frame[1] = (byte) 0xEC;
            frame[2] = (byte) command;
            frame[3] = (byte) (count >> 8);
            frame[4] = (byte) count;
            for (int i = 0; i < count; i++) frame[5 + i] = (byte) (data[i] ^ (xor ? 0xA5 : 0));
        } else {
            frame[1] = (byte) (count + 1);
            frame[2] = (byte) command;
            System.arraycopy(data, 0, frame, 3, count);
        }
        int sum = v2 ? 0 : 0x2019, end = frame.length - (v2 ? 1 : 2);
        for (int i = 0; i < end; i++) sum += frame[i] & 255;
        if (!v2) frame[end++] = (byte) (sum >> 8);
        frame[end] = (byte) sum;
        return frame;
    }

    static final class Frame {
        final int command;
        final boolean v2;
        final byte[] wireData;
        Frame(int command, boolean v2, byte[] data) {
            this.command = command; this.v2 = v2; this.wireData = data;
        }
        byte[] payload(boolean xor) {
            byte[] data = wireData.clone();
            if (v2 && xor) for (int i = 0; i < data.length; i++) data[i] ^= (byte) 0xA5;
            return data;
        }
    }

    /** 逐字节追加，保留分片尾部；校验失败时仅移走一个字节重新找帧头。 */
    static final class Decoder {
        private final byte[] pending = new byte[518];
        private int size;
        synchronized void reset() { size = 0; }
        synchronized List<Frame> feed(byte[] bytes, int length) {
            List<Frame> frames = new ArrayList<Frame>();
            for (int at = 0; at < length; at++) {
                if (size == pending.length) drop(1);
                pending[size++] = bytes[at];
                while (size > 0) {
                    int head = pending[0] & 255;
                    if (head != 0xBB && head != 0xCC) { drop(1); continue; }
                    if (size < 3) break;
                    boolean v2 = (pending[1] & 255) == 0xEC;
                    if (v2 && size < 5) break;
                    int lengthField = v2 ? ((pending[3] & 255) << 8) | (pending[4] & 255) : pending[1] & 255;
                    if (v2 ? lengthField > 512 : lengthField < 1 || lengthField > 64) {
                        drop(1); continue;
                    }
                    int total = lengthField + (v2 ? 6 : 4);
                    if (size < total) break;
                    int end = total - (v2 ? 1 : 2), sum = v2 ? 0 : 0x2019;
                    for (int i = 0; i < end; i++) sum += pending[i] & 255;
                    int received = v2 ? pending[end] & 255
                            : ((pending[end] & 255) << 8) | (pending[end + 1] & 255);
                    if (received != (sum & (v2 ? 255 : 65535))) { drop(1); continue; }
                    frames.add(new Frame(pending[2] & 255, v2, Arrays.copyOfRange(pending, v2 ? 5 : 3, end)));
                    drop(total);
                }
            }
            return frames;
        }
        private void drop(int count) {
            System.arraycopy(pending, count, pending, 0, size - count);
            size -= count;
        }
    }

    /** F2 的后续字段尚无统一盒电量语义，只接受已证实的左右耳。 */
    static int[] battery(int command, byte[] p) {
        if (command == 0xD0 && p.length == 1) {
            int value = p[0] & 255;
            return value <= 100 ? new int[]{value, 255, 255, -1} : null;
        }
        if (command == 0xF2 && p.length == 6) {
            int left = p[1] & 255, right = p[2] & 255;
            if (left > 100 || right > 100) return null;
            return new int[]{left == 0 ? 255 : left, right == 0 ? 255 : right, 255, -1};
        }
        return null;
    }

    static int mode(int group, int value) {
        if (group == 0x10) {
            if (value == 1 || value == 2) return TwsClient.MODE_ANC;
            if (value == 4) return TwsClient.MODE_TRANS;
            if (value == 5) return TwsClient.MODE_OFF;
        } else if (group == 0x1B) {
            if (value >= 1 && value <= 3) return TwsClient.MODE_ANC;
            if (value == 5) return TwsClient.MODE_TRANS;
            if (value == 6) return TwsClient.MODE_OFF;
        } else if (group == 0x17) {
            if (value == 1) return TwsClient.MODE_ANC;
            if (value == 2) return TwsClient.MODE_TRANS;
            if (value == 3) return TwsClient.MODE_OFF;
        } else if (group == 0) {
            if (value == 1) return TwsClient.MODE_OFF;
            if (value == 2) return TwsClient.MODE_ANC;
            if (value == 3) return TwsClient.MODE_TRANS;
        }
        return -1;
    }

    static byte[] modeCommand(boolean v2, int group, int mode) {
        if (mode < 0 || mode > 2) return null;
        int wire = -1;
        if (!v2) wire = mode == TwsClient.MODE_ANC ? 2 : mode == TwsClient.MODE_OFF ? 1 : 3;
        else if (group == 0x10) wire = mode == TwsClient.MODE_ANC ? 1 : mode == TwsClient.MODE_OFF ? 5 : 4;
        else if (group == 0x1B) wire = mode == TwsClient.MODE_ANC ? 1 : mode == TwsClient.MODE_OFF ? 6 : 5;
        else if (group == 0x17) wire = mode == TwsClient.MODE_ANC ? 1 : mode == TwsClient.MODE_OFF ? 3 : 2;
        if (wire < 0) return null;
        return v2 ? new byte[]{(byte) group, (byte) wire} : new byte[]{(byte) wire};
    }
    private EdifierProtocol() { }
}

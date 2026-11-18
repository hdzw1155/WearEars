package com.hdz.wearcontrol.vivo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** GAIA 已知响应的有效值校验；错误状态不能当作真实电量或功能状态发布。 */
public final class VivoProtocol {
    private VivoProtocol() { }

    public static final class Frame {
        public final int vendor, command;
        public final byte[] payload;
        Frame(int vendor, int command, byte[] payload) {
            this.vendor = vendor;
            this.command = command;
            this.payload = payload;
        }
    }

    /** 保留半包；伪帧头只移走一字节，不吞掉已读入的后续有效帧头。 */
    public static final class Decoder {
        private final byte[] buffer = new byte[263];
        private int size;
        public List<Frame> feed(byte[] bytes, int length) {
            List<Frame> result = new ArrayList<Frame>();
            if (bytes == null || length < 0 || length > bytes.length) return result;
            for (int i = 0; i < length; i++) {
                if (size == buffer.length) drop(1);
                buffer[size++] = bytes[i];
                while (size > 0) {
                    if ((buffer[0] & 255) != 255
                            || size >= 2 && buffer[1] != 3 && buffer[1] != 4
                            || size >= 3 && buffer[2] != 0) { drop(1); continue; }
                    if (size < 8) break;
                    int total = 8 + (buffer[3] & 255);
                    if (size < total) break;
                    result.add(new Frame(((buffer[4] & 255) << 8) | (buffer[5] & 255),
                            ((buffer[6] & 255) << 8) | (buffer[7] & 255), Arrays.copyOfRange(buffer, 8, total)));
                    drop(total);
                }
            }
            return result;
        }
        private void drop(int count) {
            System.arraycopy(buffer, count, buffer, 0, size - count);
            size -= count;
        }
    }

    public static int[] battery(byte[] payload) {
        if (payload == null || payload.length < 5 || payload[0] != 0) return null;
        int[] result = new int[4];
        for (int i = 0; i < 3; i++) {
            int value = payload[i + 1] & 255;
            if (value > 100 && value != 255) return null;
            result[i] = value;
        }
        int flags = payload[4] & 255;
        result[3] = flags <= 7 ? flags : -1;
        return result;
    }

    /** null=无效应答；-1=应答有效，但当前不属于界面的三个标准挡位。 */
    public static Integer noise(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int mode = payload[1] & 255;
        return mode <= 2 ? mode : -1;
    }

    public static Boolean spatial(byte[] payload) {
        return spatial(payload, false);
    }

    /** 空间音频状态来自 0x8239 报告。写入回执可能只返回结果码或回显目标值。 */
    public static Boolean spatial(byte[] payload, boolean acknowledgement) {
        if (acknowledgement || payload == null || payload.length < 1 || payload.length > 3) return null;
        if (payload.length >= 2 && payload[0] != 0) return null;
        // Air3 Pro 已有实机记录 [00, state, extension]；第三字节不是开关。
        int state = payload[payload.length == 1 ? 0 : 1] & 255;
        return state <= 1 ? Boolean.valueOf(state == 1) : null;
    }

    public static Integer deepX(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int mode = payload[1] & 255;
        return mode <= 3 || mode == 5 ? Integer.valueOf(mode) : null;
    }
}

package com.hdz.wearcontrol.vivo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    /** 0x8249 双设备状态解码结果（只读）。 */
    public static final class MultipointInfo {
        public final int totalCount;
        public final int connectedCount;
        public final List<String> connectedNames;

        MultipointInfo(int totalCount, int connectedCount, List<String> connectedNames) {
            this.totalCount = totalCount;
            this.connectedCount = connectedCount;
            this.connectedNames = Collections.unmodifiableList(connectedNames);
        }

        public String summary() {
            if (connectedNames.isEmpty()) {
                return "BT " + connectedCount + " connected";
            }
            return "BT " + connectedCount + " (" + String.join(", ", connectedNames) + ")";
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

    public static byte[] frame(int verb, int vendor, int cmd, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        if (len > 255) return null;
        byte[] f = new byte[8 + len];
        f[0] = (byte) 0xFF;
        f[1] = (byte) verb;
        f[2] = 0x00;
        f[3] = (byte) len;
        f[4] = (byte) (vendor >> 8);
        f[5] = (byte) vendor;
        f[6] = (byte) (cmd >> 8);
        f[7] = (byte) cmd;
        if (payload != null && len > 0) System.arraycopy(payload, 0, f, 8, len);
        return f;
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
        Integer mode = spatialMode(payload, acknowledgement);
        if (mode == null || mode > 1) return null;
        return Boolean.valueOf(mode == 1);
    }

    /** TWS 5 三态空间音频解码：0=关闭, 1=固定, 2=头部追踪；仅 0x8239 报告有效。 */
    public static Integer spatialMode(byte[] payload, boolean acknowledgement) {
        if (acknowledgement || payload == null || payload.length < 1 || payload.length > 3) return null;
        if (payload.length >= 2 && payload[0] != 0) return null;
        // Air3 Pro 已有实机记录 [00, state, extension]；第三字节不是开关。
        int mode = payload[payload.length == 1 ? 0 : 1] & 255;
        return mode <= 2 ? Integer.valueOf(mode) : null;
    }

    public static Integer deepX(byte[] payload) {
        return deepX(payload, false);
    }

    public static Integer deepX(byte[] payload, boolean allowSurround) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int mode = payload[1] & 255;
        if (mode <= 3 || mode == 5 || (allowSurround && mode == 4)) return Integer.valueOf(mode);
        return null;
    }

    /**
     * 实时佩戴状态 0x820D: [00, flags]
     * wire bits: bit0=L_in_case, bit1=R_in_case, bit2=L_worn, bit3=R_worn
     * 映射为 DeviceState.WEAR_* bitmask: bit0=LeftInEar, bit1=RightInEar (0..3)
     */
    public static Integer wearStatus(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int flags = payload[1] & 0xFF;
        int wear = ((flags & 0x04) != 0 ? 1 : 0) | ((flags & 0x08) != 0 ? 2 : 0);
        return Integer.valueOf(wear);
    }

    /** 游戏低延迟 0x8251: [00, 0|1] */
    public static Integer lowLatency(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int state = payload[1] & 0xFF;
        return state <= 1 ? Integer.valueOf(state) : null;
    }

    /** 佩戴检测开关 0x8203: 必须为 2 字节 [00, 0|1]；空包或单字节为通知注册回执。 */
    public static Integer wearDetection(byte[] payload) {
        if (payload == null || payload.length != 2 || payload[0] != 0) return null;
        int state = payload[1] & 0xFF;
        return state <= 1 ? Integer.valueOf(state) : null;
    }

    /** 查找耳机 ACK 0x8120: [00, 01] -> 3(双耳响铃), [00, 00] -> 0(停止) */
    public static Integer findBuds(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int state = payload[1] & 0xFF;
        if (state == 0) return Integer.valueOf(0);
        if (state == 1) return Integer.valueOf(3);
        return null;
    }

    /** 固件版本 0x821C: 优先识别可打印 ASCII 版本串，否则按二进制 [2].[5].[6] 解码。 */
    public static String firmware(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int asciiEnd = payload.length;
        while (asciiEnd > 1 && payload[asciiEnd - 1] == 0) asciiEnd--;
        if (asciiEnd > 1) {
            boolean printableAscii = true;
            boolean hasDigit = false;
            for (int i = 1; i < asciiEnd; i++) {
                int b = payload[i] & 0xFF;
                if (b < 0x20 || b > 0x7E) {
                    printableAscii = false;
                    break;
                }
                if (b >= '0' && b <= '9') hasDigit = true;
            }
            if (printableAscii && hasDigit) {
                String text = new String(payload, 1, asciiEnd - 1, StandardCharsets.US_ASCII).trim();
                if (!text.isEmpty()) return text;
            }
        }
        if (payload.length >= 7) {
            int major = payload[2] & 0xFF;
            int minor = payload[5] & 0xFF;
            int patch = payload[6] & 0xFF;
            if ((major | minor | patch) != 0) {
                return major + "." + minor + "." + patch;
            }
        }
        return null;
    }

    /** 双设备连接状态 0x8249 (只读): [00][count][MAC:6][CoD:3][state:1][nameLen:1][UTF-8]... */
    public static MultipointInfo multipoint(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int count = payload[1] & 0xFF;
        int pos = 2;
        int connected = 0;
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            if (pos + 11 > payload.length) return null;
            int state = payload[pos + 9] & 0xFF;
            int nameLen = payload[pos + 10] & 0xFF;
            pos += 11;
            if (pos + nameLen > payload.length) return null;
            if (state == 1 || state == 2) {
                connected++;
                String name = new String(payload, pos, nameLen, StandardCharsets.UTF_8).trim();
                if (!name.isEmpty()) names.add(name);
            }
            pos += nameLen;
        }
        return new MultipointInfo(count, connected, names);
    }
}

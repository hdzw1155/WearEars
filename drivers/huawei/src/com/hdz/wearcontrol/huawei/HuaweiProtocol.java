package com.hdz.wearcontrol.huawei;

import com.hdz.wearcontrol.OptionItem;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Huawei / Honor SPP model normalization, TLV validation, and extended feature codecs. */
public final class HuaweiProtocol {
    public static final int GROUP_BATTERY = 0x01;
    public static final int GROUP_ANC = 0x2B;

    public static final int CMD_DEVICE_INFO = 0x07;
    public static final int CMD_BATTERY_QUERY = 0x08;
    public static final int CMD_BATTERY_REPORT = 0x27;

    public static final int CMD_ANC_REPORT = 0x03;
    public static final int CMD_ANC_SWITCH = 0x04;
    public static final int CMD_WEAR_WRITE = 0x10;
    public static final int CMD_WEAR_READ = 0x11;
    public static final int CMD_LIVE_WEAR = 0x25;
    public static final int CMD_ANC_QUERY = 0x2A;
    public static final int CMD_DUAL_WRITE = 0x2E;
    public static final int CMD_DUAL_READ = 0x2F;
    public static final int CMD_EQ_WRITE = 0x49;
    public static final int CMD_EQ_QUERY = 0x4A;
    public static final int CMD_LOW_LATENCY = 0x6C;
    public static final int CMD_FEATURE_B4 = 0xB4;

    public static final int FEATURE_SPATIAL = 0x18;
    public static final int FEATURE_WIND = 0x1D;

    public static final OptionItem[] TRANS_OPTIONS = {
            new OptionItem(0x02, "标准通透", "Standard", OptionItem.ROLE_DEFAULT),
            new OptionItem(0x01, "人声增强", "Voice Boost", OptionItem.ROLE_VOICE)
    };

    // Huawei 0x18 spatial mode wire values: 0=Off, 1=Head Tracking, 2=Fixed (opposite of vivo 1/2).
    public static final OptionItem[] SPATIAL_OPTIONS = {
            new OptionItem(0, "关闭", "Off"),
            new OptionItem(2, "固定", "Fixed"),
            new OptionItem(1, "头部追踪", "Head Tracking")
    };

    public static final OptionItem[] SPATIAL_SCENE_OPTIONS = {
            new OptionItem(0, "默认", "Default"),
            new OptionItem(1, "有声剧场", "Theater"),
            new OptionItem(2, "电影院", "Cinema"),
            new OptionItem(3, "音乐厅", "Concert")
    };

    private static final OptionItem[] ANC_LEVELS_STD = {
            new OptionItem(1, "轻度", "Cozy", OptionItem.ROLE_DEFAULT),
            new OptionItem(0, "均衡", "General", OptionItem.ROLE_DEFAULT),
            new OptionItem(2, "深度", "Ultra", OptionItem.ROLE_DEFAULT),
            new OptionItem(3, "智慧动态", "Dynamic", OptionItem.ROLE_ADAPTIVE)
    };

    private static final OptionItem[] ANC_LEVELS_PRO2 = {
            new OptionItem(1, "轻度", "Cozy", OptionItem.ROLE_DEFAULT),
            new OptionItem(0, "均衡", "General", OptionItem.ROLE_DEFAULT),
            new OptionItem(2, "深度", "Ultra", OptionItem.ROLE_DEFAULT)
    };

    private static final OptionItem[] ANC_LEVELS_SWAPPED = {
            new OptionItem(0, "轻度", "Cozy", OptionItem.ROLE_DEFAULT),
            new OptionItem(2, "均衡", "General", OptionItem.ROLE_DEFAULT),
            new OptionItem(3, "深度", "Ultra", OptionItem.ROLE_DEFAULT),
            new OptionItem(1, "智慧动态", "Dynamic", OptionItem.ROLE_ADAPTIVE)
    };

    private HuaweiProtocol() { }

    public static String normalize(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static boolean isHonor(String name) {
        if (name == null) return false;
        return name.toLowerCase(Locale.ROOT).contains("honor") || name.contains("荣耀");
    }

    public static boolean isPro3Or7i(String name) {
        if (isHonor(name)) return false;
        return normalize(name).matches("(huawei)?freebuds(pro3|7i)");
    }

    public static boolean isStdLevels(String name) {
        if (isHonor(name)) return false;
        return normalize(name).matches("(huawei)?freebuds(5i|6i|se4anc|pro2|pro5)");
    }

    public static boolean isPro4(String name) {
        if (isHonor(name)) return false;
        return normalize(name).matches("(huawei)?freebudspro4");
    }

    public static boolean supportsTransBoost(String name) {
        if (isHonor(name)) return false;
        return normalize(name).matches("(huawei)?freebuds(6i|pro2|pro5)");
    }

    public static boolean supportsMultipoint(String name) {
        if (isHonor(name)) return false;
        return normalize(name).matches("(huawei)?(freebuds(5i|6i|7i|7|se4anc|pro2|pro3|pro4|pro5)|freeclip2)");
    }

    public static OptionItem[] ancLevelOptions(String name) {
        if (isHonor(name)) return null;
        if (isPro3Or7i(name)) return ANC_LEVELS_SWAPPED.clone();
        if (normalize(name).matches("(huawei)?freebudspro2")) return ANC_LEVELS_PRO2.clone();
        if (isStdLevels(name)) return ANC_LEVELS_STD.clone();
        return null;
    }

    public static OptionItem[] transModeOptions(String name) {
        return supportsTransBoost(name) ? TRANS_OPTIONS.clone() : null;
    }

    /** Encodes a Huawei 5A SPP frame: 5A | len(2B BE) | 00 | group | cmd | params | CRC16-XMODEM(2B BE). */
    public static byte[] frame(int group, int cmd, byte[] params) {
        int plen = params == null ? 0 : params.length;
        int len = plen + 3;
        byte[] f = new byte[6 + plen + 2];
        f[0] = (byte) 0x5A;
        f[1] = (byte) (len >> 8);
        f[2] = (byte) len;
        f[3] = (byte) 0x00;
        f[4] = (byte) group;
        f[5] = (byte) cmd;
        if (plen > 0) System.arraycopy(params, 0, f, 6, plen);
        int crc = crc16Xmodem(f, 0, 6 + plen);
        f[6 + plen] = (byte) (crc >> 8);
        f[7 + plen] = (byte) crc;
        return f;
    }

    /** CRC16/XMODEM: poly=0x1021, init=0, no reflection, no final XOR. */
    public static int crc16Xmodem(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                else crc = (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    public static byte[] windQueryParams() {
        return new byte[]{0x01, 0x01, FEATURE_WIND, 0x02, 0x00};
    }

    public static byte[] windWriteParams(boolean on) {
        return new byte[]{0x01, 0x01, FEATURE_WIND, 0x02, 0x01, (byte) (on ? 0x01 : 0x00)};
    }

    public static byte[] spatialQueryParams() {
        return new byte[]{0x01, 0x01, FEATURE_SPATIAL, 0x02, 0x00, 0x03, 0x00};
    }

    public static byte[] spatialModeWriteParams(int mode) {
        return new byte[]{0x01, 0x01, FEATURE_SPATIAL, 0x02, 0x01, (byte) mode};
    }

    public static byte[] spatialSceneWriteParams(int scene) {
        return new byte[]{0x01, 0x01, FEATURE_SPATIAL, 0x03, 0x01, (byte) scene};
    }

    /**
     * Checks that all TLVs in [off, off + len) are well-formed and that any 0x7F ACK TLV
     * carries the exact success code 00 01 86 A0 (100000).
     */
    public static boolean tlvsValidAndOk(byte[] b, int off, int len) {
        if (b == null || off < 0 || len < 0 || off > b.length - len) return false;
        int end = off + len;
        int p = off;
        while (p < end) {
            if (p + 2 > end) return false;
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) return false;
            if (type == 0x7F) {
                if (tlen != 4
                        || (b[v] & 0xFF) != 0x00
                        || (b[v + 1] & 0xFF) != 0x01
                        || (b[v + 2] & 0xFF) != 0x86
                        || (b[v + 3] & 0xFF) != 0xA0) {
                    return false;
                }
            }
            p = v + tlen;
        }
        return true;
    }

    public static int findTlvByte(byte[] b, int off, int len, int targetType) {
        if (b == null || off < 0 || len < 0 || off > b.length - len) return -1;
        int end = off + len;
        int p = off;
        while (p + 2 <= end) {
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) return -1;
            if (type == targetType && tlen >= 1) {
                return b[v] & 0xFF;
            }
            p = v + tlen;
        }
        return -1;
    }

    public static String findTlvString(byte[] b, int off, int len, int targetType) {
        if (b == null || off < 0 || len < 0 || off > b.length - len) return "";
        int end = off + len;
        int p = off;
        while (p + 2 <= end) {
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) return "";
            if (type == targetType && tlen > 0) {
                return new String(b, v, tlen, StandardCharsets.UTF_8).replace("\0", "").trim();
            }
            p = v + tlen;
        }
        return "";
    }

    public static int decodeWindReply(byte[] b, int off, int len) {
        if (!tlvsValidAndOk(b, off, len)) return -1;
        if (findTlvByte(b, off, len, 1) != FEATURE_WIND) return -1;
        int val = findTlvByte(b, off, len, 2);
        return (val == 0 || val == 1) ? val : -1;
    }

    /** Returns {mode, scene} (-1 if absent/invalid), or null if not feature 0x18 or error ACK. */
    public static int[] decodeSpatialReply(byte[] b, int off, int len) {
        if (!tlvsValidAndOk(b, off, len)) return null;
        if (findTlvByte(b, off, len, 1) != FEATURE_SPATIAL) return null;
        int mode = findTlvByte(b, off, len, 2);
        int scene = findTlvByte(b, off, len, 3);
        int validMode = (mode >= 0 && mode <= 2) ? mode : -1;
        int validScene = (scene >= 0 && scene <= 3) ? scene : -1;
        if (validMode < 0 && validScene < 0) return null;
        return new int[]{validMode, validScene};
    }

    public static int decodeTlvBool(byte[] b, int off, int len, int targetType) {
        if (!tlvsValidAndOk(b, off, len)) return -1;
        int val = findTlvByte(b, off, len, targetType);
        return (val == 0 || val == 1) ? val : -1;
    }

    /** Returns {leftInEar (0/1/-1), rightInEar (0/1/-1)} or null if invalid. */
    public static int[] decodeLiveWear(byte[] b, int off, int len, int leftTag, int rightTag) {
        if (!tlvsValidAndOk(b, off, len)) return null;
        int rawLeft = findTlvByte(b, off, len, leftTag);
        int rawRight = findTlvByte(b, off, len, rightTag);
        if (rawLeft < 0 && rawRight < 0) return null;
        int left = rawLeft < 0 ? -1 : (rawLeft == 1 ? 1 : 0);
        int right = rawRight < 0 ? -1 : (rawRight == 1 ? 1 : 0);
        return new int[]{left, right};
    }

    public static String decodeFirmwareInfo(byte[] b, int off, int len) {
        if (!tlvsValidAndOk(b, off, len)) return "";
        return findTlvString(b, off, len, 7);
    }
}

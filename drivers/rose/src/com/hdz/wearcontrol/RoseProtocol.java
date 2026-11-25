package com.hdz.wearcontrol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** RoseLink 普通 RS / V2 帧；型号先选协议，禁止用超时猜另一帧族。 */
final class RoseProtocol {
    static final int RS = 0, V2 = 1;
    private static final byte[] RS_QUERY = {
        (byte) 0xFA, 1, 7, 8, 9, 12, 13, 14, 18, 42, 43, 44, 45, 46, 51
    };
    private static final byte[] V2_QUERY = {
        (byte) 0xFA, 1, 7, 8, 9, 12, 13, 14, 18, 42, 43, 44, 45, 46, 47,
        49, 50, 51, 54, 55, 56, 57, 58, 59, 60, 61, 63, 69, 70, 73
    };

    static int familyForName(String name) {
        String n = normalize(name);
        if (n.equals("ceramicsu") || n.equals("ceramicsuhsc2710")
                || n.equals("ceramicsmk2") || n.equals("ceramicsmk25736")
                || n.equals("ceramicsmk28912f") || n.equals("sportfeel8912f")
                || n.equals("openfeell") || n.equals("openfeelmk2")
                || n.equals("budsfeelmk2") || n.equals("astrox")) return V2;
        if (n.equals("openfun") || n.equals("openlink") || n.equals("openfree")
                || n.equals("openfeel") || n.equals("sportfree")
                || n.equals("budsfree") || n.equals("budsfreei")) return RS;
        return -1; // CERAMICS / X、AGATE 属 F8；不冒充 V2。
    }

    static boolean supportsNoise(String name) {
        String n = normalize(name);
        return familyForName(name) >= 0 && !n.startsWith("open") && !n.startsWith("sport");
    }

    private static String normalize(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.startsWith("roseselsa")) return n.substring(9);
        if (n.startsWith("rose")) return n.substring(4);
        if (n.startsWith("ruoshui")) return n.substring(7);
        return n;
    }

    static byte[] query(int family, int sequence) {
        return encode(family, sequence, family == RS ? RS_QUERY : V2_QUERY);
    }

    static byte[] encode(int family, int sequence, byte[] body) {
        if ((family != RS && family != V2) || body == null || body.length < 1 || body.length > 255)
            throw new IllegalArgumentException("RoseLink body");
        byte[] packet = new byte[body.length + (family == RS ? 4 : 5)];
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) (family == RS ? 0 : sequence);
        packet[2] = (byte) body.length;
        System.arraycopy(body, 0, packet, 3, body.length);
        if (family == V2) packet[packet.length - 2] = (byte) sum(packet, 0, packet.length - 2);
        packet[packet.length - 1] = (byte) 0xAA;
        return packet;
    }

    static int[] battery(int family, byte[] value) {
        if (value == null || value.length != 3) return null;
        int[] result = {255, 255, 255, 0};
        boolean anyValid = false;
        for (int i = 0; i < 3; i++) {
            int raw = value[i] & 255;
            boolean hasFlag = family == V2 || i < 2;
            int level = hasFlag ? raw & 127 : raw;
            if (level > 100) continue;
            result[i] = level;
            anyValid = true;
            if (hasFlag && (raw & 128) != 0) result[3] |= 1 << i;
        }
        return anyValid ? result : null;
    }

    static final int ITEM_IN_EAR = 0x08;
    static final int ITEM_NOISE = 0x09;
    static final int ITEM_BATTERY = 0x0C;
    static final int ITEM_GAME = 0x0E;
    static final int ITEM_EQ = 0x2A;
    static final int ITEM_ANC_DEPTH = 0x2C;

    static final int WIND_WIRE = 4;

    static final SoundPreset[] EQ_PRESETS = {
            new SoundPreset(0, "HiFi 原声", "HiFi"),
            new SoundPreset(1, "流行", "Pop"),
            new SoundPreset(2, "摇滚", "Rock"),
            new SoundPreset(3, "古典轻柔", "Classic"),
            new SoundPreset(4, "自定义", "Custom")
    };

    static int mode(int value) {
        if (value == 1) return Controls.MODE_ANC;
        if (value == 2) return Controls.MODE_OFF;
        if (value == 3) return Controls.MODE_TRANS;
        return -1;
    }

    static boolean isWind(int value) {
        return value == WIND_WIRE;
    }

    static boolean isAncDepth(int value) {
        return value == 1 || value == 3 || value == 5;
    }

    static boolean isEqPreset(int value) {
        return value >= 0 && value < EQ_PRESETS.length;
    }

    /** RoseLink RS/V2 佩戴检测(08)极性反转: 0x00=ON, 0x01=OFF。 */
    static Boolean inEarFromWire(int raw) {
        if (raw == 0) return Boolean.TRUE;
        if (raw == 1) return Boolean.FALSE;
        return null;
    }

    static byte inEarToWire(boolean on) {
        return (byte) (on ? 0 : 1);
    }

    static final class Item {
        final int type;
        final byte[] value;
        Item(int type, byte[] value) { this.type = type; this.value = value; }
    }

    static final class Decoder {
        private final int family;
        private final byte[] buffer = new byte[4096];
        private int size;
        Decoder(int family) { this.family = family; }
        synchronized void reset() { size = 0; }

        synchronized List<Item> feed(byte[] bytes, int length) {
            List<Item> result = new ArrayList<Item>();
            if (bytes == null || length < 0 || length > bytes.length) return result;
            for (int i = 0; i < length; i++) {
                if (size == buffer.length) drop(1);
                buffer[size++] = bytes[i];
                parse(result);
            }
            return result;
        }

        private void parse(List<Item> result) {
            while (size > 0) {
                int header = buffer[0] & 255;
                if (header != 0xDD && (family != V2 || header != 0xFF)) { drop(1); continue; }
                if (size < 4) return;
                if (family == RS) {
                    int length = buffer[2] & 255;
                    if (length < 1 || buffer[1] != 0) { drop(1); continue; }
                    int total = length + 4;
                    if (size < total) return;
                    if ((buffer[total - 1] & 255) != 0xAA) { drop(1); continue; }
                    result.add(new Item(buffer[3] & 255, Arrays.copyOfRange(buffer, 4, total - 1)));
                    drop(total);
                    continue;
                }
                int end = v2End(0);
                if (end == 0) {
                    for (int start = 1; start + 5 < size; start++) {
                        int candidate = buffer[start] & 255;
                        if ((candidate == 0xDD || candidate == 0xFF) && v2End(start) > 0) {
                            drop(start);
                            end = -1;
                            break;
                        }
                    }
                    if (end == 0) return;
                    continue;
                }
                if (end < 0) {
                    int discard = -end;
                    int incomplete = -1;
                    // 校验错误的包里可能藏着完整或尚在分片的真帧；先找完整帧，否则保留最早的未完帧头。
                    for (int start = 1; start < discard; start++) {
                        int candidate = buffer[start] & 255;
                        if (candidate != 0xDD && candidate != 0xFF) continue;
                        int candidateEnd = v2End(start);
                        if (candidateEnd > 0) {
                            discard = start;
                            incomplete = -1;
                            break;
                        }
                        if (candidateEnd == 0 && incomplete < 0) incomplete = start;
                    }
                    if (incomplete > 0) discard = incomplete;
                    drop(discard);
                    continue;
                }
                for (int p = 2; p < end - 2; p += 1 + (buffer[p] & 255)) {
                    int next = p + 1 + (buffer[p] & 255);
                    result.add(new Item(buffer[p + 1] & 255, Arrays.copyOfRange(buffer, p + 2, next)));
                }
                drop(end);
            }
        }

        /** 正数为帧尾，0 为半包；负数为已确认无效的长度（只用于起点 0）。 */
        private int v2End(int start) {
            int offset = start + 2;
            int firstBadEnd = 0;
            while (offset + 1 < size) {
                if (offset > start + 2 && (buffer[offset + 1] & 255) == 0xAA) {
                    int check = buffer[offset] & 255;
                    if (sum(buffer, start, offset) == check || sum(buffer, start + 2, offset) == check) {
                        return offset + 2;
                    }
                    if (firstBadEnd == 0) firstBadEnd = -(offset + 2);
                }
                int length = buffer[offset] & 255;
                if (length == 0 || offset + 1 + length > buffer.length - 2) {
                    return firstBadEnd != 0 ? firstBadEnd : -1;
                }
                if (offset + 1 + length > size) return 0;
                offset += 1 + length;
            }
            return 0;
        }

        private void drop(int count) {
            System.arraycopy(buffer, count, buffer, 0, size - count);
            size -= count;
        }
    }

    private static int sum(byte[] bytes, int from, int end) {
        int value = 0;
        for (int i = from; i < end; i++) value += bytes[i] & 255;
        return value & 255;
    }
}

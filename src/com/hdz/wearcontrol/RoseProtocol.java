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
        if (value.length != 3) return null;
        int[] result = {255, 255, 255, 0};
        for (int i = 0; i < 3; i++) {
            int raw = value[i] & 255;
            boolean hasFlag = family == V2 || i < 2;
            int level = hasFlag ? raw & 127 : raw;
            if (level > 100) continue;
            result[i] = level;
            if (hasFlag && (raw & 128) != 0) result[3] |= 1 << i;
        }
        return result;
    }

    static int mode(int value) {
        if (value == 1) return TwsClient.MODE_ANC;
        if (value == 2) return TwsClient.MODE_OFF;
        if (value == 3) return TwsClient.MODE_TRANS;
        return -1;
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
                int offset = 2;
                boolean invalid = false, complete = false;
                int discard = 1;
                while (offset + 1 < size) {
                    if (offset > 2 && (buffer[offset + 1] & 255) == 0xAA) {
                        int check = buffer[offset] & 255;
                        if (sum(buffer, 0, offset) != check && sum(buffer, 2, offset) != check) {
                            invalid = true;
                            discard = offset + 2;
                            break;
                        }
                        for (int p = 2; p < offset; p += 1 + (buffer[p] & 255)) {
                            int end = p + 1 + (buffer[p] & 255);
                            result.add(new Item(buffer[p + 1] & 255, Arrays.copyOfRange(buffer, p + 2, end)));
                        }
                        drop(offset + 2);
                        complete = true;
                        break;
                    }
                    int length = buffer[offset] & 255;
                    if (length == 0 || offset + 1 + length > buffer.length - 2) {
                        invalid = true;
                        break;
                    }
                    if (offset + 1 + length > size) return;
                    offset += 1 + length;
                }
                if (invalid) { drop(discard); continue; }
                if (!complete) return;
            }
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

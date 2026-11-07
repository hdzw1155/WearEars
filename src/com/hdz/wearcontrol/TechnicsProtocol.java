package com.hdz.wearcontrol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Technics EAH-AZ 的 RACE 子集，不适用于 EAH-A800 头戴式耳机。 */
final class TechnicsProtocol {
    static boolean matches(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return n.matches("(technics)?(eah)?az(40|40m2|60|60m2|70w|80|100)");
    }

    static byte[] command(int id, byte... data) {
        int length = data.length + 2;
        byte[] frame = new byte[length + 4];
        frame[0] = 5; frame[1] = 0x5A;
        frame[2] = (byte) length; frame[3] = (byte) (length >> 8);
        frame[4] = (byte) id; frame[5] = (byte) (id >> 8);
        System.arraycopy(data, 0, frame, 6, data.length);
        return frame;
    }

    static int[] battery(byte[] frame) {
        if (frame.length < 6) return null;
        int type = frame[1] & 255, id = (frame[4] & 255) | ((frame[5] & 255) << 8);
        if (id == 0x0CD6 && type == 0x5D && frame.length == 9 && frame[6] == 0
                && (frame[7] == 0 || frame[7] == 1) && (frame[8] & 255) <= 100) {
            return new int[]{frame[7] == 0 ? 1 : 0, frame[8] & 255};
        }
        if (id == 0x0040 && type == 0x5B && frame.length == 8 && frame[6] == 0 && (frame[7] & 255) <= 100)
            return new int[]{2, frame[7] & 255};
        return null;
    }

    static int mode(byte[] frame) {
        if (frame.length != 10 || frame[1] != 0x5B || frame[4] != 10 || frame[5] != 0
                || frame[6] != 0 || (frame[8] & 255) > 100 || (frame[9] & 255) > 100) return -1;
        if (frame[7] == 0) return TwsClient.MODE_OFF;
        if (frame[7] == 1) return TwsClient.MODE_ANC;
        if (frame[7] == 2) return TwsClient.MODE_TRANS;
        return -1;
    }

    static byte[][] noiseCommands(int mode) {
        byte[] adaptiveOff = command(0x0068, (byte) 0);
        if (mode == TwsClient.MODE_OFF) return new byte[][]{
            adaptiveOff, command(0x000B, (byte) 0, (byte) 100, (byte) 50)};
        if (mode == TwsClient.MODE_ANC) return new byte[][]{
            adaptiveOff, command(0x000B, (byte) 1, (byte) 100, (byte) 50),
            command(0x0039, (byte) 32, (byte) 2)};
        if (mode == TwsClient.MODE_TRANS) return new byte[][]{
            adaptiveOff, command(0x0022, (byte) 0, (byte) 0),
            command(0x000B, (byte) 2, (byte) 100, (byte) 50)};
        return null;
    }

    static final class Decoder {
        private final byte[] buffer = new byte[516];
        private int size;
        List<byte[]> feed(byte[] data) {
            List<byte[]> result = new ArrayList<byte[]>();
            for (byte value : data) {
                if (size == buffer.length) drop(1);
                buffer[size++] = value;
                while (size > 0) {
                    if (buffer[0] != 5) { drop(1); continue; }
                    if (size < 4) break;
                    int type = buffer[1] & 255;
                    int length = (buffer[2] & 255) | ((buffer[3] & 255) << 8);
                    if (length < 2 || length > 512 || type < 0x5A || type > 0x5D) { drop(1); continue; }
                    if (size < length + 4) break;
                    if (type == 0x5B || type == 0x5D) result.add(Arrays.copyOf(buffer, length + 4));
                    drop(length + 4);
                }
            }
            return result;
        }
        private void drop(int count) {
            System.arraycopy(buffer, count, buffer, 0, size - count);
            size -= count;
        }
    }
}

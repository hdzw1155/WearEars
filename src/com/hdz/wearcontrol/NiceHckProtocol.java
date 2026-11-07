package com.hdz.wearcontrol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 原道长度帧；保留半包，噪声字节不占用后续读循环的缓冲。 */
final class NiceHckProtocol {
    private final byte[] buffer = new byte[4099];
    private int size;
    synchronized void reset() { size = 0; }
    synchronized List<byte[]> feed(byte[] bytes, int length) {
        List<byte[]> result = new ArrayList<byte[]>();
        for (int i = 0; i < length; i++) {
            if (size == buffer.length) drop(1);
            buffer[size++] = bytes[i];
            while (size > 0) {
                if (buffer[0] != 0x4E) { drop(1); continue; }
                if (size < 3) break;
                int count = (buffer[1] & 255) | ((buffer[2] & 255) << 8);
                if (count < 3 || count > 4096) { drop(1); continue; }
                int total = count + 3;
                if (size < total) break;
                if (buffer[3] == 0) result.add(Arrays.copyOf(buffer, total));
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

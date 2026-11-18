package com.hdz.wearcontrol;

/** 磁贴点击的设备绑定与去重规则；不把宿主重发的旧状态再次变成蓝牙指令。 */
final class TileCommand {
    final long token;
    final int mode;

    private TileCommand(long token, int mode) { this.token = token; this.mode = mode; }

    static String id(long token, int mode, String address) { return token + "/" + mode + "/" + address; }

    static TileCommand parse(String id, String address, long issued, long consumed, int mask) {
        if (id == null || address == null || address.length() == 0) return null;
        String[] parts = id.split("/", -1);
        if (parts.length != 3 || !address.equals(parts[2])) return null;
        try {
            long token = Long.parseLong(parts[0]);
            int mode = Integer.parseInt(parts[1]);
            if (token <= 0 || token > issued || token < consumed || mode < -1 || mode > 2) return null;
            if (token == consumed && (mask & (1 << (mode + 1))) != 0) return null;
            return new TileCommand(token, mode);
        } catch (NumberFormatException invalid) { return null; }
    }
}

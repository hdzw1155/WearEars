package com.hdz.wearcontrol.huawei;


import com.hdz.wearcontrol.WireDriver;
import com.hdz.wearcontrol.Controls;

import java.util.UUID;

/**
 * 华为 FreeBuds / HONOR 的 SPP 协议客户端(RFCOMM)。
 *
 * 帧格式: 5A | len(2B BE) | 00 | group(1) | cmd(1) | params | CRC16-XMODEM(2B BE)
 *   len = 参数区长度 + 3, 总长 = len + 5, CRC 覆盖 CRC 之前的全部字节。
 *   TLV: type(1) | len(1) | value。
 *
 * 电量: group 01 cmd 08 查询(参数为空 TLV 01/02/03), 上报 cmd 27。
 *   TLV01 = 整机电量; TLV02 = [左, 右, 仓](0 表示未连接); TLV03 = 充电位(整机级)。
 *
 * 降噪: group 2B cmd 04 写, 参数 TLV1 = [01, 02, mode, level]
 *   mode: 00 关 / 01 降噪 / 02 通透; level: mode=0 用 00, 否则 FF。
 *   group 2B cmd 2A 读, 参数 TLV1/02; 应答 TLV1 = [level, mode](mode 在第二字节)。
 *   写没有回执, 400ms 后补一次 2A 读回确认; 耳机按键改档会主动发 2B 03。
 *
 * SE4 的 SPP 监听在信道 1(旧款 5i/4i 在 16), SDP 找不到时反射 createRfcommSocket 兜底。
 */
public class HuaweiSppClient extends WireDriver {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int GROUP_BATTERY = 0x01;
    private static final int GROUP_ANC = 0x2B;

    private static final int CMD_BATTERY_QUERY = 0x08;
    private static final int CMD_BATTERY_REPORT = 0x27;
    private static final int CMD_ANC_SWITCH = 0x04;
    private static final int CMD_ANC_QUERY = 0x2A;
    private static final int CMD_ANC_REPORT = 0x03;

    private static final int CHANNEL_SE4 = 1;
    private static final int CHANNEL_LEGACY = 16;

    private static final int MAX_FRAME = 1024;

    private static final byte[] BATTERY_QUERY_PARAMS = {
            0x01, 0x00, 0x02, 0x00, 0x03, 0x00
    };
    private static final byte[] ANC_QUERY_PARAMS = {
            0x01, 0x00, 0x02, 0x00
    };

    private final byte[] stage = new byte[2048];
    private int stageLen;
    private volatile boolean noiseConfirmed;
    private volatile HuaweiSoundProtocol.State equalizer;
    private volatile String connectedName = "";

    @Override protected String logTag() { return "HwClient"; }
    @Override protected String unnamedDevice() { return null; }
    @Override protected boolean cancelDiscovery() { return true; }
    @Override protected String connectionFailure() { return "找不到 SPP 服务(信道 1/16)，耳机需先与手表配对"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{SPP_UUID}; }
    @Override protected int[] channels() { return new int[]{CHANNEL_SE4, CHANNEL_LEGACY}; }
    @Override protected void prepare() {
        noiseConfirmed = false;
        equalizer = null;
        connectedName = deviceName();
        stageLen = 0;
    }
    @Override protected void opened() {
        ready();
        queryBattery();
        schedule(new Runnable() { @Override public void run() { queryNoise(); }}, 250);
    }

    @Override
    public String brandName() {
        return "Huawei";
    }

    @Override
    public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0) | (equalizer != null ? CAP_EQUALIZER : 0);
    }

    @Override
    public int verifLevel() {
        // FreeBuds 6i / FreeBuds SE 4 ANC / HONOR Earbuds X9 已在本项目真机验证;
        // 其他华为/荣耀型号沿用同一 SPP 协议族, 如实标实验性。
        String raw = connectedName == null ? "" : connectedName;
        String n = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.equals("huaweifreebuds6i") || n.equals("freebuds6i")
                || n.equals("huaweifreebudsse4anc") || n.equals("freebudsse4anc")
                || n.equals("honorearbudsx9") || n.equals("earbudsx9")) {
            return VERIF_REAL;
        }
        return VERIF_UNTESTED;
    }

    // ---- 命令 ----

    @Override
    public void queryBattery() {
        writeFrame(GROUP_BATTERY, CMD_BATTERY_QUERY, BATTERY_QUERY_PARAMS);
    }

    @Override
    public void queryNoise() {
        writeFrame(GROUP_ANC, CMD_ANC_QUERY, ANC_QUERY_PARAMS);
    }

    @Override
    public void changeNoise(int mode, Runnable flushed) {
        if (!noiseConfirmed) return;
        if (mode != MODE_ANC && mode != MODE_OFF && mode != MODE_TRANS) return;
        int wire;
        if (mode == MODE_ANC) wire = 0x01;
        else if (mode == MODE_TRANS) wire = 0x02;
        else wire = 0x00;
        byte level = (byte) (wire == 0x00 ? 0x00 : 0xFF);
        byte[] params = {0x01, 0x02, (byte) wire, level};
        writeThen(frame(GROUP_ANC, CMD_ANC_SWITCH, params), () -> { flushed.run(); schedule(this::queryNoise, 400); });
    }

    /** 组帧并发送: 5A len(2B BE) 00 group cmd params CRC16(大端) */
    private void writeFrame(int group, int cmd, byte[] params) { write(frame(group, cmd, params)); }

    private static byte[] frame(int group, int cmd, byte[] params) {
        int plen = params == null ? 0 : params.length;
        int len = plen + 3;
        final byte[] f = new byte[6 + plen + 2];
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

    /** CRC16/XMODEM: poly=0x1021, init=0, 无反射, 无最终 XOR */
    static int crc16Xmodem(byte[] data, int off, int len) {
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

    // ---- 接收 ----

    @Override protected void received(byte[] data) {
        int r = data.length;
        if (stageLen + r > stage.length) {
            stageLen = 0; // 防异常数据撑爆
            trace("HwClient", "RFCOMM receive buffer overflow");
            return;
        }
        System.arraycopy(data, 0, stage, stageLen, r);
        stageLen += r;

        int off = 0;
        while (true) {
            int head = findHead(stage, off, stageLen);
            if (head < 0) {
                off = stageLen;
                break;
            }
            if (head + 5 > stageLen) {
                off = head;
                break;
            }
            int len = ((stage[head + 1] & 0xFF) << 8) | (stage[head + 2] & 0xFF);
            int total = len + 5;
            if (len < 3 || total > MAX_FRAME) {
                off = head + 1;
                continue;
            }
            if (head + total > stageLen) {
                off = head;
                break;
            }
            int expect = ((stage[head + total - 2] & 0xFF) << 8)
                    | (stage[head + total - 1] & 0xFF);
            boolean ok = (stage[head + 3] & 0xFF) == 0x00
                    && crc16Xmodem(stage, head, total - 2) == expect;
            if (ok) {
                dispatchFrame(stage, head, total);
                off = head + total;
            } else {
                off = head + 1;
            }
            if (off >= stageLen) break;
        }
        if (off > 0) {
            if (off < stageLen) {
                System.arraycopy(stage, off, stage, 0, stageLen - off);
                stageLen -= off;
            } else {
                stageLen = 0;
            }
        }
        if (stageLen == stage.length) stageLen = 0;
    }

    private static int findHead(byte[] b, int from, int len) {
        for (int i = from; i < len; i++) {
            if ((b[i] & 0xFF) == 0x5A) return i;
        }
        return -1;
    }

    private void dispatchFrame(byte[] b, int head, int total) {
        int group = b[head + 4] & 0xFF;
        int cmd = b[head + 5] & 0xFF;
        int paramsOff = head + 6;
        int paramsLen = total - 8;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            sb.append(String.format("%02X ", b[head + i]));
        }
        trace("HwClient", "rx g=0x" + Integer.toHexString(group)
                + " c=0x" + Integer.toHexString(cmd) + " [" + sb.toString().trim() + "]");

        if (group == GROUP_BATTERY
                && (cmd == CMD_BATTERY_QUERY || cmd == CMD_BATTERY_REPORT)) {
            parseBattery(b, paramsOff, paramsLen);
        } else if (group == GROUP_ANC && cmd == CMD_ANC_QUERY) {
            parseAncState(b, paramsOff, paramsLen);
        } else if (group == GROUP_ANC && cmd == 0x4A && HuaweiSoundProtocol.supportsQuery(connectedName)) {
            HuaweiSoundProtocol.State report = HuaweiSoundProtocol.decode(b, paramsOff, paramsLen);
            if (report != null) {
                equalizer = report;
                events().onEqualizer(report.current);
            }
        } else if (group == GROUP_ANC && cmd == CMD_ANC_REPORT) {
            // 耳机按键改了档位, 重新查询一次
            queryNoise();
        }
    }

    /** 电量 TLV: 01 整机电量 / 02 左右仓电量 / 03 充电位(整机级) */
    private void parseBattery(byte[] b, int off, int len) {
        int end = off + len;
        int p = off;
        int overall = -1;
        int l = 0xFF, r = 0xFF, c = 0xFF;
        int flags = 0;
        while (p + 2 <= end) {
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) break;
            if (type == 1 && tlen >= 1) {
                overall = b[v] & 0xFF;
            } else if (type == 2 && tlen >= 3) {
                l = b[v] & 0xFF;
                r = b[v + 1] & 0xFF;
                c = b[v + 2] & 0xFF;
            } else if (type == 3) {
                for (int i = 0; i < tlen; i++) {
                    if ((b[v + i] & 0x01) != 0) {
                        flags = 1 | 2 | 4;
                        break;
                    }
                }
            }
            p = v + tlen;
        }
        trace("HwClient", "battery overall=" + overall + " L/R/C=" + l + "/" + r + "/" + c
                + " flags=" + flags);
        events().onBattery(normPct(l), normPct(r), normPct(c), flags);
    }

    /** ANC 读回应答: TLV1 = [level, mode], mode 在第二字节 */
    private void parseAncState(byte[] b, int off, int len) {
        int end = off + len;
        int p = off;
        while (p + 2 <= end) {
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) break;
            if (type == 1 && tlen >= 2) {
                int mode = b[v + 1] & 0xFF;
                int ui;
                if (mode == 0x01) ui = MODE_ANC;
                else if (mode == 0x02) ui = MODE_TRANS;
                else if (mode == 0x00) ui = MODE_OFF;
                else ui = -1;
                if (ui >= 0) noiseConfirmed = true;
                trace("HwClient", "anc level=" + (b[v] & 0xFF) + " mode=" + mode + " ui=" + ui);
                events().onNoiseMode(ui);
                return;
            }
            p = v + tlen;
        }
    }

    private static int normPct(int v) {
        if (v >= 1 && v <= 100) return v;
        return 0xFF;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
    @Override public int potentialCaps() {
        return caps() | CAP_NOISE | (HuaweiSoundProtocol.supportsQuery(connectedName) ? CAP_EQUALIZER : 0);
    }

    @Override public com.hdz.wearcontrol.SoundPreset[] equalizerPresets() {
        HuaweiSoundProtocol.State report = equalizer;
        return report == null ? new com.hdz.wearcontrol.SoundPreset[0] : report.presets.clone();
    }
    @Override public void queryEqualizer() {
        if (HuaweiSoundProtocol.supportsQuery(connectedName))
            writeFrame(GROUP_ANC, 0x4A, new byte[]{1, 0, 2, 0, 3, 0});
    }
    @Override public void setEqualizer(int preset) {
        for (com.hdz.wearcontrol.SoundPreset option : equalizerPresets()) {
            if (option.id == preset) {
                writeFrame(GROUP_ANC, 0x49, new byte[]{1, 1, (byte) preset});
                return;
            }
        }
    }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.contains("freebuds") || n.contains("huawei") || n.contains("honor") || n.contains("华为") || n.contains("荣耀");
        }
        @Override public WireDriver create() { return new HuaweiSppClient(); }
    }

}

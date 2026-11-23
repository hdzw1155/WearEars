package com.hdz.wearcontrol.oppo;


import com.hdz.wearcontrol.WireDriver;
import com.hdz.wearcontrol.Controls;

import java.util.UUID;

/**
 * OPPO Enco / realme / OnePlus 耳机(OPOv1 / HeyMelody 协议)客户端, 经典蓝牙 RFCOMM。
 *
 * 帧: AA | TotalLen(LEB128) | slice(1) | 00 | cmd(2B LE) | seq(1) | payLen(2B LE) | payload
 *   TotalLen = 7 + payLen(整帧); 无校验。
 *
 * 握手: 连上后 300ms 发 0x0100(空), 之后每 200ms 依次
 *   0x0103 产品ID → 0x0200 → 0x0205 订阅 → 0x010D → 0x010C 查降噪 →
 *   0x0106 查电量 → 0x0109 佩戴 → 0x0108 → 0x0105。
 *   0x8100 应答里带命令位图; 0x8103 应答里带产品 ID(Air5 Pro = 06 C4 10)。
 *
 * 降噪: 0x0404 写, payload = 01 01 <mask>: 关=0x01 / 降噪=0x02 / 通透=0x04。
 *   读 0x010C, payload = 01 01; 应答/推送里的状态位与 SET 不同:
 *   关=0x0008 / 降噪=0x0002 / 通透=0x0100。
 *   耳机主动推送 0x0204, subtype 0x01=电量, 0x02=佩戴, 0x03=降噪(事件 payload 03 01 01 <mask LE>)。
 * 电量: 0x0106 查询, 应答 0x8106 = [00][count](part, raw)..., part 1左/2右/3盒;
 *   raw & 0x7F 电量(0 未知), raw & 0x80 充电。
 */
public class OppoOpoClient extends WireDriver {

    private static final UUID UUID_OPPO =
            UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5");
    private static final UUID UUID_FALLBACK =
            UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5");
    private static final int RFCOMM_CHANNEL = 15;

    private static final int CMD_HELLO = 0x0100;
    private static final int CMD_PRODUCT = 0x0103;
    private static final int CMD_BATTERY_QUERY = 0x0106;
    private static final int CMD_ANC_QUERY = 0x010C;
    private static final int CMD_WEAR_QUERY = 0x0109;
    private static final int CMD_ANC_SET = 0x0404;
    private static final int CMD_CAPS = 0x0200;
    private static final int CMD_SUBSCRIBE = 0x0205;
    private static final int CMD_010D = 0x010D;
    private static final int CMD_0108 = 0x0108;
    private static final int CMD_0105 = 0x0105;

    private static final int RSP_BATTERY = 0x8106;
    private static final int RSP_ANC = 0x810C;
    private static final int RSP_ANC_ACK = 0x8404;
    private static final int RSP_HELLO = 0x8100;
    private static final int RSP_PRODUCT = 0x8103;
    private static final int RSP_WEAR = 0x8109;
    private static final int CMD_EVENT = 0x0204;

    private static final int MASK_ANC_SET_OFF = 0x01;
    private static final int MASK_ANC_SET_ANC = 0x02;
    private static final int MASK_ANC_SET_TRANS = 0x04;

    private OppoProtocol.Decoder decoder;
    private volatile boolean protocolReady;
    private volatile boolean noiseConfirmed;
    private volatile String connectedName = "";
    private int seq = 1;
    private int initialMode = -1;
    private int[] initialBattery;

    @Override protected String logTag() { return "OppoClient"; }
    @Override protected String unnamedDevice() { return null; }
    @Override protected boolean cancelDiscovery() { return true; }
    @Override protected String connectionFailure() { return "找不到 OPPO 控制服务(信道 15)，耳机需先与手表配对"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{UUID_OPPO, UUID_FALLBACK}; }
    @Override protected int[] channels() { return new int[]{RFCOMM_CHANNEL}; }
    @Override protected void prepare() {
        protocolReady = noiseConfirmed = false;
        connectedName = deviceName();
        seq = 1;
        decoder = new OppoProtocol.Decoder();
    }
    @Override protected void opened() {
        initStep(0, 300);
    }
    @Override protected void closed() { protocolReady = false; }

    private static final int[] INIT_COMMANDS = {CMD_HELLO, CMD_PRODUCT, CMD_CAPS, CMD_SUBSCRIBE, CMD_010D,
            CMD_ANC_QUERY, CMD_BATTERY_QUERY, CMD_WEAR_QUERY, CMD_0108, CMD_0105};

    private void initStep(final int index, long delay) {
        schedule(new Runnable() { @Override public void run() {
            if (index == INIT_COMMANDS.length) {
                markReady();
                return;
            }
            int command = INIT_COMMANDS[index];
            byte[] payload = command == CMD_SUBSCRIBE ? new byte[]{0x03, 0x01, 0x02, 0x03}
                    : command == CMD_ANC_QUERY ? new byte[]{0x01, 0x01} : null;
            writeThen(frame(command, nextSeq(), payload), () ->
                    initStep(index + 1, index + 1 == INIT_COMMANDS.length ? 400 : 200));
        }}, delay);
    }

    @Override
    public String brandName() {
        return "OPPO";
    }

    @Override
    public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0);
    }

    @Override
    public int verifLevel() {
        return OppoProtocol.isVerifiedReal(connectedName) ? VERIF_REAL : VERIF_UNTESTED;
    }

    private synchronized int nextSeq() {
        int s = seq & 0xFF;
        seq = (seq + 1) & 0xFF;
        if (seq == 0) seq = 1;
        return s;
    }

    private void markReady() {
        if (protocolReady) return;
        protocolReady = true;
        ready();
        trace("OppoClient", "init done, ready");
        queryBattery();
        queryNoise();
        if (initialMode >= 0) events().onNoiseMode(initialMode);
        if (initialBattery != null) events().onBattery(initialBattery[0], initialBattery[1], initialBattery[2], initialBattery[3]);
    }

    // ---- 命令 ----

    @Override
    public void queryBattery() {
        if (!protocolReady) return;
        writeFrame(CMD_BATTERY_QUERY, 0xF0, null);
    }

    @Override
    public void queryNoise() {
        if (!protocolReady) return;
        writeFrame(CMD_ANC_QUERY, 0xF1, new byte[]{0x01, 0x01});
    }

    @Override
    public synchronized void changeNoise(int mode, Runnable flushed) {
        if (mode != MODE_ANC && mode != MODE_OFF && mode != MODE_TRANS) return;
        if (!protocolReady || !noiseConfirmed) return;
        int mask;
        if (mode == MODE_ANC) mask = MASK_ANC_SET_ANC;
        else if (mode == MODE_TRANS) mask = MASK_ANC_SET_TRANS;
        else mask = MASK_ANC_SET_OFF;
        writeThen(frame(CMD_ANC_SET, 0xF3, new byte[]{0x01, 0x01, (byte) mask}), () -> {
            flushed.run();
            schedule(this::queryNoise, 350);
        });
    }

    /** 组帧: AA TotalLen(LEB128) 00 00 cmd(2B LE) seq payLen(2B LE) payload */
    private void writeFrame(int cmd, int seqNo, byte[] payload) { write(frame(cmd, seqNo, payload)); }

    private static byte[] frame(int cmd, int seqNo, byte[] payload) {
        int payLen = payload == null ? 0 : payload.length;
        int totalLen = 7 + payLen;
        int lebLen = totalLen < 0x80 ? 1 : 2;
        final byte[] f = new byte[1 + lebLen + 7 + payLen];
        int p = 0;
        f[p++] = (byte) 0xAA;
        if (lebLen == 1) {
            f[p++] = (byte) totalLen;
        } else {
            f[p++] = (byte) ((totalLen & 0x7F) | 0x80);
            f[p++] = (byte) ((totalLen >> 7) & 0x7F);
        }
        f[p++] = 0x00;                       // slice
        f[p++] = 0x00;
        f[p++] = (byte) (cmd & 0xFF);        // cmd LE
        f[p++] = (byte) ((cmd >> 8) & 0xFF);
        f[p++] = (byte) seqNo;
        f[p++] = (byte) (payLen & 0xFF);     // payLen LE
        f[p++] = (byte) ((payLen >> 8) & 0xFF);
        if (payLen > 0) System.arraycopy(payload, 0, f, p, payLen);
        return f;
    }

    @Override protected void received(byte[] data) {
        for (OppoProtocol.Frame frame : decoder.feed(data, data.length)) dispatch(frame);
    }

    private void dispatch(OppoProtocol.Frame frame) {
        int cmd = frame.command;
        int seqNo = frame.sequence;
        byte[] payload = frame.payload;
        trace("OppoClient", "rx cmd=0x" + Integer.toHexString(cmd)
                + " seq=" + seqNo + " " + toHex(payload));

        if (cmd == RSP_BATTERY) {
            parseBattery(payload, false);
        } else if (cmd == RSP_ANC) {
            parseAncState(payload);
        } else if (cmd == CMD_EVENT) {
            parseEvent(payload);
        } else if (cmd == RSP_ANC_ACK) {
            parseAncAck(payload);
        } else if (cmd == RSP_HELLO) {
            trace("OppoClient", "hello caps " + toHex(payload));
        } else if (cmd == RSP_PRODUCT) {
            trace("OppoClient", "product id " + toHex(payload));
        } else if (cmd == RSP_WEAR) {
            trace("OppoClient", "wear " + toHex(payload));
        }
    }

    private void parseBattery(byte[] payload, boolean event) {
        int[] value = OppoProtocol.battery(payload, event);
        if (value == null) return;
        if (protocolReady) events().onBattery(value[0], value[1], value[2], value[3]);
        else initialBattery = value;
    }

    private void parseEvent(byte[] payload) {
        if (payload.length < 2) return;
        int subtype = payload[0] & 0xFF;
        if (subtype == 0x03) {
            // ANC 事件: 03 01 01 <mask LE>; 03 02 01 ... 是查询回声, 忽略
            if (payload.length == 5 && payload[1] == 0x01 && payload[2] == 0x01) {
                int mask = (payload[3] & 0xFF) | ((payload[4] & 0xFF) << 8);
                fireAncMask(mask);
            }
        } else if (subtype == 0x01) {
            parseBattery(payload, true);
        } else if (subtype == 0x02) {
            trace("OppoClient", "wear event " + toHex(payload));
        }
    }

    private void parseAncState(byte[] payload) {
        int mask = OppoProtocol.noiseMask(payload);
        if (mask >= 0) fireAncMask(mask);
    }

    private void fireAncMask(int mask) {
        int ui = OppoProtocol.noiseMode(mask);
        if (ui < 0) return;
        noiseConfirmed = true;
        trace("OppoClient", "anc mask=0x" + Integer.toHexString(mask) + " ui=" + ui);
        if (protocolReady) events().onNoiseMode(ui);
        else initialMode = ui;
    }

    private void parseAncAck(byte[] payload) {
        if (payload.length >= 1) {
            int status = payload[0] & 0xFF;
            if (status != 0x00) {
                trace("OppoClient", "anc set ack status=0x" + Integer.toHexString(status));
                if (status == 0x0E) {
                    // 未佩戴时切换可能被拒绝, 读回真实状态
                    queryNoise();
                }
            }
        }
    }

    // ---- 工具 ----

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
    @Override public int potentialCaps() { return caps() | CAP_NOISE; }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.contains("oppo") || n.contains("enco") || n.contains("oneplus") || n.contains("realme");
        }
        @Override public WireDriver create() { return new OppoOpoClient(); }
    }

}

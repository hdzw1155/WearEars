package com.hdz.wearcontrol.vivo;

import com.hdz.wearcontrol.WireDriver;
import com.hdz.wearcontrol.Controls;


import java.util.UUID;

/**
 * vivo TWS (Air3 Pro 实机验证) GAIA-over-RFCOMM 客户端。
 * 帧格式: FF | VERB | 00 | LEN | VENDOR(2,大端) | CMD(2,大端) | PAYLOAD(LEN)
 * 协议来源: HyperEars / TWS-Pods-PC 公开逆向文档。
 */
public class VivoGaiaClient extends WireDriver {

    public static final UUID GAIA_UUID =
            UUID.fromString("00000837-d102-11e1-9b23-00025b00a5a5");

    /** A2DP Sink, 用于兜底识别任意已配对耳机 */
    public static final UUID A2DP_SINK_UUID =
            UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb");

    public static final int VENDOR_QUALCOMM = 0x000A;
    public static final int VENDOR_VIVO = 0x001B;

    public static final int CMD_HANDSHAKE = 0x0300;
    public static final int CMD_NOISE_SET = 0x0130;
    public static final int CMD_NOISE_QUERY = 0x0230;
    public static final int CMD_BATT_QUERY = 0x0207;

    // 空间音频: 命令号来自反编译推断(TWS-Pods-PC),未官方证实,格式按开关类 [state] 模式
    public static final int CMD_SPATIAL_SET = 0x0139;
    public static final int CMD_SPATIAL_QUERY = 0x0239;
    public static final int RESP_SPATIAL_ACK = 0x8139;
    public static final int RESP_SPATIAL = 0x8239;

    // DeepX 音效: 命令号与格式已真机验证(Air3 Pro)——SET 单字节 [mode], ACK 0x8118 回 [00,mode],
    // 查询 0x8218 回 [00,mode]; mode 0=标准 1=清澈人声 2=超重低音, 3=清亮高音 5=悠扬听书。
    public static final int CMD_DEEPX_SET = 0x0118;
    public static final int CMD_DEEPX_QUERY = 0x0218;
    public static final int RESP_DEEPX_ACK = 0x8118;
    public static final int RESP_DEEPX = 0x8218;

    public static final int MODE_ANC = 0x00;
    public static final int MODE_OFF = 0x01;
    public static final int MODE_TRANS = 0x02;

    /** 机型线格式差异,参数来自 HyperEars VivoTwsProtocol.WireConfig */
    public static final int CFG_AIR3_PRO = 0;  // v3, 设置载荷 mode 04 00 (实机抓包)
    public static final int CFG_FAMILY_V4 = 1; // v4, mode 03 01 (家族默认画像)
    public static final int CFG_TWS_3E = 2;    // v3, mode 03 (TWS 3e 文档)

    /** 按蓝牙设备名猜机型线格式,未知名字用家族默认 */
    public static int cfgForName(String name) {
        if (name == null) return CFG_FAMILY_V4;
        String n = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.contains("air3")) return CFG_AIR3_PRO;
        if (n.contains("3e")) return CFG_TWS_3E;
        if (n.contains("tws3")) return CFG_TWS_3E; // TWS 3/3 Pro 与 3e 同代,推断同配置
        return CFG_FAMILY_V4;
    }

    public static final int RESP_NOISE_SET = 0x8130;
    public static final int RESP_NOISE_QUERY = 0x8230;
    public static final int RESP_BATT = 0x8207;

    private VivoProtocol.Decoder decoder;
    private volatile String connectedName = "";
    private volatile boolean noiseConfirmed, spatialConfirmed, deepxConfirmed;
    private volatile int cfg = CFG_FAMILY_V4;

    @Override protected String logTag() { return "GaiaClient"; }
    @Override protected String unnamedDevice() { return null; }
    @Override protected long failureCooldown() { return 2000; }
    @Override protected String connectionFailure() {
        return "连接失败(耳机未与手表配对? 或耳机已占满两个连接)";
    }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{GAIA_UUID}; }
    @Override protected int[] channels() { return new int[]{13}; }
    @Override protected void prepare() {
        noiseConfirmed = spatialConfirmed = deepxConfirmed = false;
        connectedName = deviceName();
        cfg = cfgForName(connectedName);
        decoder = new VivoProtocol.Decoder();
    }
    @Override protected void opened() {
        ready();
        sendHandshake();
        queryBattery();
        queryNoise();
        querySpatial();
        queryDeepX();
    }

    private void fireBattery(int l, int r, int c, int f) {
        events().onBattery(l, r, c, f);
    }

    private void fireMode(int mode) {
        events().onNoiseMode(mode);
    }

    private void fireSpatial(Boolean state) {
        events().onSpatial(state);
    }

    private void fireDeepX(Integer mode) {
        events().onDeepX(mode);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    @Override
    public String brandName() {
        return "vivo";
    }

    @Override
    public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0)
                | (spatialConfirmed ? CAP_SPATIAL : 0) | (deepxConfirmed ? CAP_DEEPX : 0);
    }

    @Override
    public int verifLevel() {
        String name = connectedName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return name.equals("vivotwsair3pro") || name.equals("twsair3pro") || name.equals("air3pro")
                ? VERIF_REAL : VERIF_UNTESTED;
    }


    // ---- 发送 ----

    private void writeFrame(int verb, int vendor, int cmd, byte[] payload) { writeFrame(verb, vendor, cmd, payload, null); }

    private void writeFrame(int verb, int vendor, int cmd, byte[] payload, Runnable flushed) {
        if (!transportOpen()) return;
        int len = payload == null ? 0 : payload.length;
        if (len > 255) { reportError("控制指令长度超出协议上限"); return; }
        final byte[] f = new byte[8 + len];
        f[0] = (byte) 0xFF;
        f[1] = (byte) verb;
        f[2] = 0x00;
        f[3] = (byte) len;
        f[4] = (byte) (vendor >> 8);
        f[5] = (byte) vendor;
        f[6] = (byte) (cmd >> 8);
        f[7] = (byte) cmd;
        if (payload != null && len > 0) System.arraycopy(payload, 0, f, 8, len);
        writeThen(f, flushed);
    }

    public void sendHandshake() {
        writeFrame(0x04, VENDOR_QUALCOMM, CMD_HANDSHAKE, null);
    }

    public void queryBattery() {
        writeFrame(0x04, VENDOR_VIVO, CMD_BATT_QUERY, null);
    }

    public void queryNoise() {
        byte[] payload = cfg == CFG_FAMILY_V4 ? new byte[]{0x00} : new byte[0];
        writeFrame(cfg == CFG_FAMILY_V4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_NOISE_QUERY, payload);
    }

    public void changeNoise(int mode, Runnable flushed) {
        if (!noiseConfirmed || mode < 0 || mode > 2) return;
        byte[] suffix;
        if (cfg == CFG_AIR3_PRO) suffix = new byte[]{0x04, 0x00};
        else if (cfg == CFG_TWS_3E) suffix = new byte[]{0x03};
        else suffix = new byte[]{0x03, 0x01};
        byte[] payload = new byte[1 + suffix.length];
        payload[0] = (byte) mode;
        System.arraycopy(suffix, 0, payload, 1, suffix.length);
        writeFrame(cfg == CFG_FAMILY_V4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_NOISE_SET, payload, flushed);
    }

    // ---- 空间音频(实验性) ----

    @Override
    public void querySpatial() {
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_SPATIAL_QUERY,
                v4 ? new byte[]{0x00} : null);
    }

    /** 空间音频开关: v3/v4 均被耳机接受,取与降噪一致的 verb; 载荷 [01]=开 [00]=关
     *  (真机探测确认: Air3 Pro 上 v3 [01] 使 0x8239 状态翻转为 01) */
    @Override
    public void setSpatial(boolean on) {
        if (!spatialConfirmed) { querySpatial(); return; }
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_SPATIAL_SET,
                new byte[]{(byte) (on ? 0x01 : 0x00)});
    }

    // ---- DeepX 音效(真机验证) ----

    @Override
    public void queryDeepX() {
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_DEEPX_QUERY,
                v4 ? new byte[]{0x00} : null);
    }

    /** DeepX 音效设置: 载荷为单字节 [mode],真实值 0/1/2/3/5(0x04 被官方跳过,悠扬听书=5),
     *  verb 按 cfg 取, ACK 0x8118 回 [00, mode],与降噪/空间音频同款套路 */
    @Override
    public void setDeepX(int mode) {
        if (!deepxConfirmed || !(mode >= 0 && mode <= 3 || mode == 5)) return;
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_DEEPX_SET,
                new byte[]{(byte) mode});
    }

    // ---- 接收 ----

    @Override protected void received(byte[] data) {
        for (VivoProtocol.Frame frame : decoder.feed(data, data.length)) {
            int vendor = frame.vendor, cmd = frame.command;
            byte[] payload = frame.payload;
            if (vendor == VENDOR_VIVO && cmd == RESP_BATT) {
                int[] value = VivoProtocol.battery(payload);
                if (value != null) fireBattery(value[0], value[1], value[2], value[3]);
            } else if (vendor == VENDOR_VIVO
                    && (cmd == RESP_NOISE_QUERY || cmd == RESP_NOISE_SET)) {
                trace("GaiaClient",
                        "noise cmd=0x" + Integer.toHexString(cmd)
                                + " payload=" + toHex(payload));
                if (payload.length >= 2) {
                    Integer mode = VivoProtocol.noise(payload);
                    if (mode != null) { noiseConfirmed = mode >= 0; fireMode(mode.intValue()); }
                }
            } else if (vendor == VENDOR_VIVO
                    && (cmd == RESP_SPATIAL || cmd == RESP_SPATIAL_ACK)) {
                trace("GaiaClient",
                        "spatial cmd=0x" + Integer.toHexString(cmd)
                                + " payload=" + toHex(payload));
                Boolean state = VivoProtocol.spatial(payload, cmd == RESP_SPATIAL_ACK);
                if (state != null) { spatialConfirmed = true; fireSpatial(state); }
            } else if (vendor == VENDOR_VIVO
                    && (cmd == RESP_DEEPX || cmd == RESP_DEEPX_ACK)) {
                trace("GaiaClient",
                        "deepx cmd=0x" + Integer.toHexString(cmd)
                                + " payload=" + toHex(payload));
                Integer mode = VivoProtocol.deepX(payload);
                if (mode != null) { deepxConfirmed = true; fireDeepX(mode); }
            } else if (vendor == VENDOR_VIVO) {
                // 未知帧全量记录,留作协议分析 ground truth
                trace("GaiaClient",
                        "unknown cmd=0x" + Integer.toHexString(cmd)
                                + " payload=" + toHex(payload));
            }
        }
    }

    @Override public int potentialCaps() { return caps() | CAP_NOISE | CAP_SPATIAL | CAP_DEEPX; }
    @Override public com.hdz.wearcontrol.SoundPreset[] equalizerPresets() { return com.hdz.wearcontrol.SoundPreset.vivo(); }
    @Override public void queryEqualizer() { queryDeepX(); }
    @Override public void setEqualizer(int preset) { setDeepX(preset); }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.contains("vivo") || n.contains("iqoo");
        }
        @Override public WireDriver create() { return new VivoGaiaClient(); }
    }

}

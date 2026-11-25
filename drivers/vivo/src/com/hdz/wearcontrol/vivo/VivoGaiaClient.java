package com.hdz.wearcontrol.vivo;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.FeatureDescriptor;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.OptionItem;
import com.hdz.wearcontrol.SoundPreset;
import com.hdz.wearcontrol.WireDriver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * vivo / iQOO TWS GAIA-over-RFCOMM 客户端。
 * 帧格式: FF | VERB | 00 | LEN | VENDOR(2,大端) | CMD(2,大端) | PAYLOAD(LEN)
 * 协议来源: 实机抓包 + HyperEars / TWS-Pods-PC / OppoPodsManager 公开逆向文档。
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
    public static final int CMD_REG_START = 0x0202;
    public static final int CMD_REG_END = 0x0206;

    public static final int CMD_NOISE_SET = 0x0130;
    public static final int CMD_NOISE_QUERY = 0x0230;
    public static final int CMD_BATT_QUERY = 0x0207;

    // 空间音频: Air3 Pro 实机仅支持开关状态；TWS 5 家族支持 0=关, 1=固定, 2=头追
    public static final int CMD_SPATIAL_SET = 0x0139;
    public static final int CMD_SPATIAL_QUERY = 0x0239;
    public static final int RESP_SPATIAL_ACK = 0x8139;
    public static final int RESP_SPATIAL = 0x8239;

    // DeepX 音效: Air3 Pro 真机验证 0/1/2/3/5(跳过 4)；iQOO TWS Air3 Pro 额外声明 4=全景环绕
    public static final int CMD_DEEPX_SET = 0x0118;
    public static final int CMD_DEEPX_QUERY = 0x0218;
    public static final int RESP_DEEPX_ACK = 0x8118;
    public static final int RESP_DEEPX = 0x8218;

    // 实时佩戴状态 (0x020D/0x820D) & 佩戴检测开关 (0x0103/0x0203)
    public static final int CMD_WEAR_DETECT_SET = 0x0103;
    public static final int CMD_WEAR_DETECT_QUERY = 0x0203;
    public static final int RESP_WEAR_DETECT_ACK = 0x8103;
    public static final int RESP_WEAR_DETECT = 0x8203;
    public static final int CMD_WEAR_STATUS_QUERY = 0x020D;
    public static final int RESP_WEAR_STATUS = 0x820D;

    // 游戏低延迟 (0x0151/0x0251)
    public static final int CMD_LOW_LATENCY_SET = 0x0151;
    public static final int CMD_LOW_LATENCY_QUERY = 0x0251;
    public static final int RESP_LOW_LATENCY_ACK = 0x8151;
    public static final int RESP_LOW_LATENCY = 0x8251;

    // 查找耳机 (0x0120/0x8120): 仅双耳同时响铃 [01] / 停止 [00]；0x0220 为播放状态，切勿误用
    public static final int CMD_FIND_BUDS = 0x0120;
    public static final int RESP_FIND_BUDS_ACK = 0x8120;

    // 固件版本 (0x021C/0x821C) & 双设备连接状态只读查询 (0x0249/0x8249)
    public static final int CMD_FIRMWARE_QUERY = 0x021C;
    public static final int RESP_FIRMWARE = 0x821C;
    public static final int CMD_MULTIPOINT_QUERY = 0x0249;
    public static final int RESP_MULTIPOINT = 0x8249;

    public static final int MODE_ANC = 0x00;
    public static final int MODE_OFF = 0x01;
    public static final int MODE_TRANS = 0x02;

    /** 机型线格式差异,参数来自 HyperEars VivoTwsProtocol.WireConfig */
    public static final int CFG_AIR3_PRO = 0;  // v3, 设置载荷 mode 04 00 (实机抓包)
    public static final int CFG_FAMILY_V4 = 1; // v4, mode 03 01 (家族默认画像)
    public static final int CFG_TWS_3E = 2;    // v3, mode 03 (TWS 3e 文档)

    private static String normalize(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** 按蓝牙设备名猜机型线格式,未知名字用家族默认 */
    public static int cfgForName(String name) {
        String n = normalize(name);
        if (n.isEmpty()) return CFG_FAMILY_V4;
        if (n.contains("air3")) return CFG_AIR3_PRO;
        if (n.contains("3e")) return CFG_TWS_3E;
        if (n.contains("tws3")) return CFG_TWS_3E; // TWS 3/3 Pro 与 3e 同代,推断同配置
        return CFG_FAMILY_V4;
    }

    public static boolean isTws5(String name) {
        return normalize(name).contains("tws5");
    }

    public static boolean isAir3(String name) {
        return normalize(name).contains("air3");
    }

    public static boolean isIqooAir3Pro(String name) {
        String n = normalize(name);
        return n.contains("iqoo") && n.contains("air3pro");
    }

    public static boolean isVerifiedAir3Pro(String name) {
        String n = normalize(name);
        return n.equals("vivotwsair3pro") || n.equals("twsair3pro") || n.equals("air3pro");
    }

    public static boolean supportsFindBuds(String name) {
        String n = normalize(name);
        return n.contains("air3") || n.contains("3e") || n.contains("tws3")
                || n.contains("tws4") || n.contains("tws5");
    }

    public static final int RESP_NOISE_SET = 0x8130;
    public static final int RESP_NOISE_QUERY = 0x8230;
    public static final int RESP_BATT = 0x8207;

    private VivoProtocol.Decoder decoder;
    private volatile String connectedName = "";
    private volatile boolean noiseConfirmed, spatialConfirmed, deepxConfirmed;
    private volatile boolean wearStatusConfirmed, lowLatencyConfirmed, wearDetectConfirmed, firmwareConfirmed;
    private volatile int cfg = CFG_FAMILY_V4;
    private volatile int lastSpatialMode;
    private volatile String firmwareVersion = "";
    private volatile String multipointSummary = "";

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
        wearStatusConfirmed = lowLatencyConfirmed = wearDetectConfirmed = firmwareConfirmed = false;
        lastSpatialMode = 0;
        firmwareVersion = "";
        multipointSummary = "";
        connectedName = deviceName();
        cfg = cfgForName(connectedName);
        decoder = new VivoProtocol.Decoder();
    }
    @Override protected void opened() {
        ready();
        sendHandshake();
        registerNotifications();
        queryBattery();
        queryNoise();
        querySpatial();
        queryDeepX();
        queryWearStatus();
        queryLowLatency();
        queryWearDetection();
        queryFirmware();
        queryMultipoint();
        if (supportsFindBuds(connectedName)) {
            confirmFeature(findBudsSpec());
        }
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
        return isVerifiedAir3Pro(connectedName) ? VERIF_REAL : VERIF_UNTESTED;
    }

    private boolean useV4() {
        return cfg == CFG_FAMILY_V4;
    }

    private FeatureDescriptor wearStatusSpec() {
        return FeatureDescriptor.readOnly(FeatureId.WEAR_STATUS, VERIF_UNTESTED, false);
    }

    private FeatureDescriptor lowLatencySpec() {
        return FeatureDescriptor.toggle(FeatureId.LOW_LATENCY, VERIF_UNTESTED,
                FeatureDescriptor.SAFETY_NONE, 0, false);
    }

    private FeatureDescriptor wearDetectSpec() {
        return FeatureDescriptor.toggle(FeatureId.WEAR_DETECTION, VERIF_UNTESTED,
                FeatureDescriptor.SAFETY_NONE, 0, false);
    }

    private FeatureDescriptor findBudsSpec() {
        return FeatureDescriptor.findBuds(
                VERIF_UNTESTED,
                false,
                new OptionItem[]{new OptionItem(3, "双耳响铃", "Ring Both")},
                "查找耳机仅支持双耳响铃；入耳检测触发时禁止响铃",
                "Find My Buds supports ringing both buds only; blocked while worn");
    }

    private FeatureDescriptor firmwareSpec() {
        return FeatureDescriptor.readOnly(FeatureId.FIRMWARE_INFO, VERIF_UNTESTED, false);
    }

    private FeatureDescriptor equalizerSpec() {
        int verif = isVerifiedAir3Pro(connectedName) ? VERIF_REAL : VERIF_UNTESTED;
        return FeatureDescriptor.choice(FeatureId.EQUALIZER, verif,
                FeatureDescriptor.SAFETY_NONE, 0, false,
                OptionItem.fromPresets(equalizerPresets()), "", "");
    }

    private FeatureDescriptor spatialSpec(boolean tws5) {
        String limitZh = tws5
                ? "TWS 5 空间音频（不支持场景预设）"
                : (isAir3(connectedName)
                        ? "仅基础开关；Air3 Pro 硬件不支持场景与头追"
                        : "仅基础开关；未确认场景与头追支持");
        String limitEn = tws5
                ? "TWS 5 spatial audio (scene presets unsupported)"
                : (isAir3(connectedName)
                        ? "Toggle only; Air3 Pro hardware lacks scenes & head tracking"
                        : "Toggle only; scenes & head tracking unconfirmed");
        return FeatureDescriptor.toggle(FeatureId.SPATIAL_AUDIO, VERIF_UNTESTED,
                FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true, limitZh, limitEn);
    }

    private FeatureDescriptor headTrackingSpec() {
        return FeatureDescriptor.toggle(FeatureId.HEAD_TRACKING, VERIF_UNTESTED,
                FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true);
    }

    @Override
    public FeatureDescriptor[] featureDescriptors() {
        List<FeatureDescriptor> list = new ArrayList<FeatureDescriptor>();
        if (supportsFindBuds(connectedName)) list.add(findBudsSpec());
        if (wearStatusConfirmed) list.add(wearStatusSpec());
        if (lowLatencyConfirmed) list.add(lowLatencySpec());
        if (wearDetectConfirmed) list.add(wearDetectSpec());
        if (deepxConfirmed) list.add(equalizerSpec());
        if (spatialConfirmed) {
            boolean tws5 = isTws5(connectedName);
            list.add(spatialSpec(tws5));
            if (tws5) list.add(headTrackingSpec());
        }
        if (firmwareConfirmed) list.add(firmwareSpec());
        return list.toArray(new FeatureDescriptor[0]);
    }

    @Override
    public void queryFeature(FeatureId id) {
        if (id == null) return;
        switch (id) {
            case SPATIAL_AUDIO:
            case HEAD_TRACKING:
                querySpatial();
                break;
            case EQUALIZER:
                queryDeepX();
                break;
            case WEAR_STATUS:
                queryWearStatus();
                break;
            case LOW_LATENCY:
                queryLowLatency();
                break;
            case WEAR_DETECTION:
                queryWearDetection();
                break;
            case FIRMWARE_INFO:
                queryFirmware();
                queryMultipoint();
                break;
            case FIND_MY_BUDS:
            default:
                break;
        }
    }

    @Override
    public void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (id == null) return;
        switch (id) {
            case SPATIAL_AUDIO:
                if (spatialConfirmed) super.writeFeature(id, value, flushed);
                break;
            case EQUALIZER:
                if (deepxConfirmed) super.writeFeature(id, value, flushed);
                break;
            case HEAD_TRACKING:
                if (isTws5(connectedName) && spatialConfirmed) {
                    setHeadTracking(value != 0, flushed);
                }
                break;
            case LOW_LATENCY:
                if (lowLatencyConfirmed) setLowLatency(value != 0, flushed);
                break;
            case WEAR_DETECTION:
                if (wearDetectConfirmed) setWearDetection(value != 0, flushed);
                break;
            case FIND_MY_BUDS:
                if (supportsFindBuds(connectedName) && (value == 0 || value == 3)) {
                    setFindBuds(value, flushed);
                }
                break;
            default:
                break;
        }
    }

    // ---- 发送 ----

    private void writeFrame(int verb, int vendor, int cmd, byte[] payload) {
        writeFrame(verb, vendor, cmd, payload, null);
    }

    private void writeFrame(int verb, int vendor, int cmd, byte[] payload, Runnable flushed) {
        if (!transportOpen()) return;
        byte[] f = VivoProtocol.frame(verb, vendor, cmd, payload);
        if (f == null) { reportError("控制指令长度超出协议上限"); return; }
        writeThen(f, flushed);
    }

    private void queryVivo(int cmd) {
        boolean v4 = useV4();
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, cmd, v4 ? new byte[]{0x00} : null);
    }

    public void sendHandshake() {
        writeFrame(0x04, VENDOR_QUALCOMM, CMD_HANDSHAKE, null);
    }

    /** Air3 Pro / TWS 3e / TWS 4 需发送 v4 0x0202..0x0206 通知注册；TWS 5 跳过。 */
    public void registerNotifications() {
        if (isTws5(connectedName)) return;
        for (int cmd = CMD_REG_START; cmd <= CMD_REG_END; cmd++) {
            writeFrame(0x04, VENDOR_VIVO, cmd, null);
        }
    }

    public void queryBattery() {
        writeFrame(0x04, VENDOR_VIVO, CMD_BATT_QUERY, null);
    }

    public void queryNoise() {
        byte[] payload = useV4() ? new byte[]{0x00} : new byte[0];
        writeFrame(useV4() ? 0x04 : 0x03, VENDOR_VIVO, CMD_NOISE_QUERY, payload);
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
        writeFrame(useV4() ? 0x04 : 0x03, VENDOR_VIVO, CMD_NOISE_SET, payload, flushed);
    }

    public void queryWearStatus() {
        queryVivo(CMD_WEAR_STATUS_QUERY);
    }

    public void queryLowLatency() {
        queryVivo(CMD_LOW_LATENCY_QUERY);
    }

    public void setLowLatency(boolean on, Runnable flushed) {
        if (!lowLatencyConfirmed) return;
        writeFrame(useV4() ? 0x04 : 0x03, VENDOR_VIVO, CMD_LOW_LATENCY_SET,
                new byte[]{(byte) (on ? 0x01 : 0x00)}, flushed);
    }

    public void queryWearDetection() {
        queryVivo(CMD_WEAR_DETECT_QUERY);
    }

    public void setWearDetection(boolean on, Runnable flushed) {
        if (!wearDetectConfirmed) return;
        writeFrame(useV4() ? 0x04 : 0x03, VENDOR_VIVO, CMD_WEAR_DETECT_SET,
                new byte[]{(byte) (on ? 0x01 : 0x00)}, flushed);
    }

    public void setFindBuds(int target, Runnable flushed) {
        if (!supportsFindBuds(connectedName) || (target != 0 && target != 3)) return;
        writeFrame(useV4() ? 0x04 : 0x03, VENDOR_VIVO, CMD_FIND_BUDS,
                new byte[]{(byte) (target == 0 ? 0x00 : 0x01)}, flushed);
    }

    public void queryFirmware() {
        queryVivo(CMD_FIRMWARE_QUERY);
    }

    public void queryMultipoint() {
        queryVivo(CMD_MULTIPOINT_QUERY);
    }

    // ---- 空间音频(实验性) ----

    @Override
    public void querySpatial() {
        queryVivo(CMD_SPATIAL_QUERY);
    }

    /** 空间音频开关: v3/v4 均被耳机接受,取与降噪一致的 verb; 载荷 [01]=开 [00]=关
     *  (真机探测确认: Air3 Pro 上 v3 [01] 使 0x8239 状态翻转为 01) */
    @Override
    public void setSpatial(boolean on) {
        if (!spatialConfirmed) { querySpatial(); return; }
        writeFrame(useV4() ? 0x04 : 0x03, VENDOR_VIVO, CMD_SPATIAL_SET,
                new byte[]{(byte) (on ? 0x01 : 0x00)});
    }

    public void setHeadTracking(boolean on, Runnable flushed) {
        if (!isTws5(connectedName) || !spatialConfirmed) return;
        writeFrame(useV4() ? 0x04 : 0x03, VENDOR_VIVO, CMD_SPATIAL_SET,
                new byte[]{(byte) (on ? 0x02 : 0x01)}, flushed);
    }

    // ---- DeepX 音效(真机验证) ----

    @Override
    public void queryDeepX() {
        queryVivo(CMD_DEEPX_QUERY);
    }

    /** DeepX 音效设置: 载荷为单字节 [mode],Air3 Pro 真实值 0/1/2/3/5(0x04 被官方跳过,悠扬听书=5);
     *  iQOO TWS Air3 Pro 额外支持 4=全景环绕。 */
    @Override
    public void setDeepX(int mode) {
        boolean allowSurround = isIqooAir3Pro(connectedName);
        if (!deepxConfirmed || !(mode >= 0 && mode <= 3 || mode == 5 || (allowSurround && mode == 4))) return;
        writeFrame(useV4() ? 0x04 : 0x03, VENDOR_VIVO, CMD_DEEPX_SET,
                new byte[]{(byte) mode});
    }

    private void emitFirmwareAndMultipoint() {
        String text;
        if (!firmwareVersion.isEmpty() && !multipointSummary.isEmpty()) {
            text = firmwareVersion + " · " + multipointSummary;
        } else if (!firmwareVersion.isEmpty()) {
            text = firmwareVersion;
        } else if (!multipointSummary.isEmpty()) {
            text = multipointSummary;
        } else {
            return;
        }
        if (!firmwareConfirmed) {
            firmwareConfirmed = true;
            confirmFeature(firmwareSpec());
        }
        reportFeatureText(FeatureId.FIRMWARE_INFO, text);
    }

    // ---- 接收 ----

    @Override protected void received(byte[] data) {
        for (VivoProtocol.Frame frame : decoder.feed(data, data.length)) {
            int vendor = frame.vendor, cmd = frame.command;
            byte[] payload = frame.payload;
            if (vendor != VENDOR_VIVO) continue;

            if (cmd == RESP_BATT) {
                int[] value = VivoProtocol.battery(payload);
                if (value != null) fireBattery(value[0], value[1], value[2], value[3]);
            } else if (cmd == RESP_NOISE_QUERY || cmd == RESP_NOISE_SET) {
                trace("GaiaClient",
                        "noise cmd=0x" + Integer.toHexString(cmd)
                                + " payload=" + toHex(payload));
                if (payload.length >= 2) {
                    Integer mode = VivoProtocol.noise(payload);
                    if (mode != null) { noiseConfirmed = true; fireMode(mode.intValue()); }
                }
            } else if (cmd == RESP_SPATIAL || cmd == RESP_SPATIAL_ACK) {
                trace("GaiaClient",
                        "spatial cmd=0x" + Integer.toHexString(cmd)
                                + " payload=" + toHex(payload));
                if (isTws5(connectedName)) {
                    Integer mode = VivoProtocol.spatialMode(payload, cmd == RESP_SPATIAL_ACK);
                    if (mode != null) {
                        lastSpatialMode = mode.intValue();
                        boolean spatialOn = mode.intValue() != 0;
                        int headTracking = mode.intValue() == 2 ? 1 : 0;
                        if (!spatialConfirmed) {
                            spatialConfirmed = true;
                            confirmFeature(spatialSpec(true));
                            confirmFeature(headTrackingSpec(), headTracking);
                        } else {
                            reportFeature(FeatureId.HEAD_TRACKING, headTracking);
                        }
                        fireSpatial(Boolean.valueOf(spatialOn));
                    }
                } else {
                    Boolean state = VivoProtocol.spatial(payload, cmd == RESP_SPATIAL_ACK);
                    if (state != null) {
                        if (!spatialConfirmed) {
                            spatialConfirmed = true;
                            confirmFeature(spatialSpec(false));
                        }
                        fireSpatial(state);
                    }
                }
            } else if (cmd == RESP_DEEPX || cmd == RESP_DEEPX_ACK) {
                trace("GaiaClient",
                        "deepx cmd=0x" + Integer.toHexString(cmd)
                                + " payload=" + toHex(payload));
                Integer mode = VivoProtocol.deepX(payload, isIqooAir3Pro(connectedName));
                if (mode != null) {
                    if (!deepxConfirmed) {
                        deepxConfirmed = true;
                        confirmFeature(equalizerSpec());
                    }
                    fireDeepX(mode);
                }
            } else if (cmd == RESP_WEAR_STATUS) {
                Integer wear = VivoProtocol.wearStatus(payload);
                trace("GaiaClient", "wearStatus payload=" + toHex(payload) + " -> " + wear);
                if (wear != null) {
                    if (!wearStatusConfirmed) {
                        wearStatusConfirmed = true;
                        confirmFeature(wearStatusSpec(), wear.intValue());
                    } else {
                        reportFeature(FeatureId.WEAR_STATUS, wear.intValue());
                    }
                }
            } else if (cmd == RESP_LOW_LATENCY) {
                Integer val = VivoProtocol.lowLatency(payload);
                trace("GaiaClient", "lowLatency payload=" + toHex(payload));
                if (val != null) {
                    if (!lowLatencyConfirmed) {
                        lowLatencyConfirmed = true;
                        confirmFeature(lowLatencySpec(), val.intValue());
                    } else {
                        reportFeature(FeatureId.LOW_LATENCY, val.intValue());
                    }
                }
            } else if (cmd == RESP_LOW_LATENCY_ACK) {
                trace("GaiaClient", "lowLatency ack payload=" + toHex(payload));
            } else if (cmd == RESP_WEAR_DETECT) {
                Integer val = VivoProtocol.wearDetection(payload);
                trace("GaiaClient", "wearDetect payload=" + toHex(payload));
                if (val != null) {
                    if (!wearDetectConfirmed) {
                        wearDetectConfirmed = true;
                        confirmFeature(wearDetectSpec(), val.intValue());
                    } else {
                        reportFeature(FeatureId.WEAR_DETECTION, val.intValue());
                    }
                }
            } else if (cmd == RESP_WEAR_DETECT_ACK) {
                trace("GaiaClient", "wearDetect ack payload=" + toHex(payload));
            } else if (cmd == RESP_FIND_BUDS_ACK) {
                Integer target = VivoProtocol.findBuds(payload);
                trace("GaiaClient", "findBuds ack payload=" + toHex(payload));
                if (target != null && supportsFindBuds(connectedName)) {
                    reportFeature(FeatureId.FIND_MY_BUDS, target.intValue());
                }
            } else if (cmd == RESP_FIRMWARE) {
                String fw = VivoProtocol.firmware(payload);
                trace("GaiaClient", "firmware payload=" + toHex(payload) + " -> " + fw);
                if (fw != null && !fw.isEmpty()) {
                    firmwareVersion = fw;
                    emitFirmwareAndMultipoint();
                }
            } else if (cmd == RESP_MULTIPOINT) {
                VivoProtocol.MultipointInfo info = VivoProtocol.multipoint(payload);
                if (info != null) {
                    multipointSummary = info.summary();
                    trace("GaiaClient", "multipoint " + multipointSummary);
                    emitFirmwareAndMultipoint();
                } else {
                    trace("GaiaClient", "multipoint invalid payload=" + toHex(payload));
                }
            } else {
                // 未知帧全量记录,留作协议分析 ground truth
                trace("GaiaClient",
                        "unknown cmd=0x" + Integer.toHexString(cmd)
                                + " payload=" + toHex(payload));
            }
        }
    }

    @Override public int potentialCaps() { return caps() | CAP_NOISE | CAP_SPATIAL | CAP_DEEPX; }
    @Override public SoundPreset[] equalizerPresets() {
        SoundPreset[] base = SoundPreset.vivo();
        if (!isIqooAir3Pro(connectedName)) return base;
        return new SoundPreset[]{
                base[0], base[1], base[2], base[3],
                new SoundPreset(4, "全景环绕", "Immersive Surround"),
                base[4]
        };
    }
    @Override public void queryEqualizer() { queryDeepX(); }
    @Override public void setEqualizer(int preset) { setDeepX(preset); }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            if (name == null) return false;
            String n = name.toLowerCase(Locale.ROOT);
            return n.contains("vivo") || n.contains("iqoo");
        }
        @Override public WireDriver create() { return new VivoGaiaClient(); }
    }

}

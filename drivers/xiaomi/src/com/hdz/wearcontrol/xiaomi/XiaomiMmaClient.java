package com.hdz.wearcontrol.xiaomi;


import com.hdz.wearcontrol.FeatureDescriptor;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.SoundPreset;
import com.hdz.wearcontrol.WireDriver;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 小米 / Redmi TWS 的 RCSP(MMA 同族)协议客户端, 经典蓝牙 RFCOMM。
 *
 * 帧格式: FE DC BA | flags | opcode | len(2B BE) | payload | EF, 无校验。
 *   flags: bit7=1 命令 / 0=应答, bit6=1 要求应答, bit2:0 = target app id。
 *   命令 payload = sn(1) | params...; 应答 payload = status(1) | sn(1) | data...
 *
 * 认证: 挑战-应答(XiaomiAuth E21, 占位地址版本), 双向:
 *   1) 我方发 0x50 [01 ‖ challenge16]; 2) 耳机回 0x50 [01 ‖ transform16];
 *   3) 我方发 0x51 [01 00]; 4) 耳机回 0x51 才算就绪。
 *   耳机也可能主动发 0x50 挑战(需回 0x50 应答帧)。应答必须匹配本会话挑战和序号，失败不进入就绪状态。
 *
 * 电量/版本/VID:PID: GET_TARGET_INFO 0x02, params FF FF FF FF; 应答属性表 len|type|value,
 *   type 1 = 固件版本, type 3 = VID(2B BE)+PID(2B BE), type 7 = [左, 右, 仓](bit7 充电, FF 未知)。
 * 三态: SET_TARGET_INFO 0x08, params 02 04 mode(00 关/01 降噪/02 通透);
 *   状态用 GET_DEVICE_CONFIG 0xF3 [00 0B] 读回, 配置 TLV: len|type(2B)|value, id 000B = [mode, level]。
 *   6 Lite 用 TARGET_INFO 写；Buds 5 / 3 Pro 用配置 11 写。0x06 是断链命令，不发送。
 */
public class XiaomiMmaClient extends WireDriver {

    private static final UUID FAST_UUID =
            UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB");
    private static final UUID MMA_UUID =
            UUID.fromString("00001101-0000-1000-8000-008584D01810");
    // SDP 解析不到时的固定信道兜底: Buds 6 Play = 6, Buds 6 Lite = 29
    private static final int[] RFCOMM_CHANNELS = {6, 29};

    private static final int APP_DEVICE = 4;

    private static final int OP_GET_TARGET_INFO = 0x02;
    private static final int OP_PUSH_TARGET_INFO = 0x04;
    private static final int OP_SET_TARGET_INFO = 0x08;
    private static final int OP_GET_TARGET_INFO_EXT = 0x09;
    private static final int OP_SET_CONFIG = 0xF2;
    private static final int OP_GET_CONFIG = 0xF3;
    private static final int OP_NOTIFY_CONFIG = 0xF4;
    private static final int OP_AUTH = 0x50;
    private static final int OP_AUTH_DONE = 0x51;

    private static final int CFG_MULTIPOINT = 0x0004;
    private static final int CFG_EQUALIZER = 0x0007;
    private static final int CFG_FIND_BUDS = 0x0009;
    private static final int CFG_CYCLE = 0x000A;
    private static final int CFG_NOISE = 0x000B;
    private static final int CFG_WEAR_STATUS = 0x000C;
    private static final int CFG_SPATIAL_SET = 0x001D;
    private static final int CFG_SPATIAL_GET = 0x001E;
    private static final int CFG_ADAPTIVE_ANC = 0x0025;
    private static final int CFG_LOW_LATENCY = 0x002F;
    private static final int CFG_SPATIAL_SCENE = 0x0036;
    private static final int CFG_SPATIAL_FIXED = 0x004F;

    private static final int AUTH_TIMEOUT_MS = 10000;

    // UI 三态 ↔ 小米线值: 0=关 1=降噪 2=通透
    private static int wireMode(int uiMode) {
        if (uiMode == MODE_ANC) return 1;
        if (uiMode == MODE_TRANS) return 2;
        return 0;
    }

    private static int uiMode(int wireMode) {
        if (wireMode == 1) return MODE_ANC;
        if (wireMode == 2) return MODE_TRANS;
        return wireMode == 0 ? MODE_OFF : -1;
    }

    private final SecureRandom random = new SecureRandom();
    private int sn;
    private int authSn = -1, authDoneSn = -1;
    private boolean authVerified;
    private volatile boolean noiseSupported, noNoiseModel, spatialConfirmed, equalizerConfirmed;
    private int profile;
    private volatile XiaomiProtocol.ModelCaps modelCaps = XiaomiProtocol.CAPS_UNKNOWN;
    private volatile FeatureDescriptor[] descriptors = new FeatureDescriptor[0];
    private final Set<FeatureId> confirmedFeatures = EnumSet.noneOf(FeatureId.class);
    private final int[] noiseLevels = new int[3];
    private int lastWireMode = -1;
    private boolean adaptiveAnc;
    private boolean spatialOn;
    private boolean headTrackingOn;
    private int wearStatus = -1;
    private int pendingFindSn = -1;
    private int pendingFindTarget;
    private int findRingingTarget;
    private int dataApp() { return profile == 0 ? APP_DEVICE : 0; }
    private volatile String connectedName = "";
    private final XiaomiProtocol.Decoder decoder = new XiaomiProtocol.Decoder();
    private byte[] challenge;

    @Override protected String logTag() { return "MmaClient"; }
    @Override protected String unnamedDevice() { return ""; }
    @Override protected boolean cancelDiscovery() { return true; }
    @Override protected String connectionFailure() { return "连接失败: 找不到控制服务(通道 6/29)"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{FAST_UUID, MMA_UUID}; }
    @Override protected int[] channels() { return RFCOMM_CHANNELS; }
    @Override protected void prepare() {
        authVerified = noiseSupported = spatialConfirmed = equalizerConfirmed = false;
        authSn = authDoneSn = -1;
        pendingFindSn = -1;
        pendingFindTarget = findRingingTarget = 0;
        wearStatus = -1;
        lastWireMode = -1;
        adaptiveAnc = spatialOn = headTrackingOn = false;
        challenge = null;
        decoder.reset();
        connectedName = deviceName();
        modelCaps = XiaomiProtocol.capsForName(connectedName);
        profile = modelCaps != XiaomiProtocol.CAPS_UNKNOWN ? modelCaps.profile : XiaomiProtocol.profile(connectedName);
        noNoiseModel = modelCaps.noNoise;
        confirmedFeatures.clear();
        rebuildDescriptors();
        java.util.Arrays.fill(noiseLevels, 0);
        sn = random.nextInt() & 0xFF;
    }

    private void rebuildDescriptors() {
        descriptors = XiaomiProtocol.descriptors(modelCaps, verifLevel());
    }

    private FeatureDescriptor findDescriptor(FeatureId id) {
        for (FeatureDescriptor spec : descriptors) {
            if (spec != null && spec.id == id) return spec;
        }
        return null;
    }

    private void confirmModelFeatures(boolean queryExtended) {
        List<Integer> cfgIds = new ArrayList<Integer>();
        boolean needWearDetect = false;
        for (FeatureDescriptor spec : descriptors) {
            if (spec == null) continue;
            if (!confirmedFeatures.contains(spec.id)) {
                confirmedFeatures.add(spec.id);
                confirmFeature(spec);
            }
            if (!queryExtended) continue;
            switch (spec.id) {
                case ANC_LEVEL:
                    if (modelCaps.adaptiveAncToggle) cfgIds.add(CFG_ADAPTIVE_ANC);
                    break;
                case EQUALIZER:
                    cfgIds.add(CFG_EQUALIZER);
                    break;
                case LOW_LATENCY:
                    cfgIds.add(CFG_LOW_LATENCY);
                    break;
                case MULTIPOINT:
                    cfgIds.add(CFG_MULTIPOINT);
                    break;
                case WEAR_STATUS:
                    cfgIds.add(CFG_WEAR_STATUS);
                    break;
                case WEAR_DETECTION:
                    needWearDetect = true;
                    break;
                case SPATIAL_AUDIO:
                    if (modelCaps.spatialKind == XiaomiProtocol.SPATIAL_HT_1D) {
                        cfgIds.add(CFG_SPATIAL_SET);
                        cfgIds.add(CFG_SPATIAL_GET);
                    } else if (modelCaps.spatialKind == XiaomiProtocol.SPATIAL_FIXED_4F) {
                        cfgIds.add(CFG_SPATIAL_FIXED);
                    }
                    break;
                case SPATIAL_SCENE:
                    cfgIds.add(CFG_SPATIAL_SCENE);
                    break;
                default:
                    break;
            }
        }
        if (!queryExtended || !isConnected()) return;
        if (!cfgIds.isEmpty()) {
            int[] ids = new int[cfgIds.size()];
            for (int i = 0; i < cfgIds.size(); i++) ids[i] = cfgIds.get(i).intValue();
            sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(ids));
        }
        if (needWearDetect) {
            sendCommand(OP_GET_TARGET_INFO_EXT, dataApp(), new byte[]{0x0A});
        }
    }
    @Override protected void opened() {
        if (profile == 1) {
            // Redmi Buds 5 的公开抓包无认证阶段，收到有效状态包后才通知连接成功。
            sendCommand(OP_GET_TARGET_INFO, dataApp(), new byte[]{-1, -1, -1, -1});
            sendCommand(OP_GET_CONFIG, dataApp(), new byte[]{0, 11});
        } else startAuth();
        schedule(new Runnable() { @Override public void run() {
            if (!isConnected()) fail("协议确认超时: 请确认耳机型号支持 RCSP 控制协议");
        }}, AUTH_TIMEOUT_MS);
    }

    @Override
    public String brandName() {
        return "Xiaomi";
    }

    @Override
    public int caps() {
        return CAP_BATTERY
                | (noiseSupported ? CAP_NOISE : 0)
                | (spatialConfirmed ? CAP_SPATIAL : 0)
                | (equalizerConfirmed ? CAP_EQUALIZER : 0);
    }

    @Override
    public int verifLevel() {
        return XiaomiProtocol.isVerifiedReal(connectedName) ? VERIF_REAL : VERIF_UNTESTED;
    }

    @Override
    public FeatureDescriptor[] featureDescriptors() {
        return descriptors.clone();
    }

    @Override
    public SoundPreset[] equalizerPresets() {
        return XiaomiProtocol.eqPresets(modelCaps.eqFamily);
    }

    // ---- 帧 ----

    /** 命令帧: FE DC BA flags op len(2B BE) sn params EF; len = params.len + 1 */
    private int sendCommand(int opcode, int app, byte[] params) { return sendCommand(opcode, app, params, null); }
    private int sendCommand(int opcode, int app, byte[] params, Runnable flushed) {
        if (!transportOpen()) return -1;
        int serial = sn; sn = (sn + 1) & 255;
        if (opcode == OP_AUTH) authSn = serial;
        if (opcode == OP_AUTH_DONE) authDoneSn = serial;
        writeThen(XiaomiProtocol.command(opcode, app, serial, params == null ? new byte[0] : params), flushed);
        return serial;
    }

    private synchronized void sendResponse(int opcode, int app, int serial, int status, byte[] data) {
        if (transportOpen()) writeRaw(XiaomiProtocol.response(opcode, app, serial, status,
                data == null ? new byte[0] : data));
    }

    private void writeRaw(byte[] frame) {
        write(frame, "tx " + XiaomiAuth.toHex(frame));
    }

    // ---- 认证 ----

    private void startAuth() {
        challenge = new byte[16];
        random.nextBytes(challenge);
        byte[] params = new byte[17];
        params[0] = 0x01;
        System.arraycopy(challenge, 0, params, 1, 16);
        authSn = sendCommand(OP_AUTH, XiaomiProtocol.authApp(profile), params);
    }

    private void onAuthResponse(int serial, byte[] data) {
        if (serial != authSn) return;
        if (!XiaomiProtocol.authMatches(challenge, data)) {
            fail("耳机认证应答不匹配，连接已停止");
            return;
        }
        authVerified = true;
        authDoneSn = sendCommand(OP_AUTH_DONE, XiaomiProtocol.authApp(profile), new byte[]{0x01, 0x00});
    }

    /** 耳机主动发起的 0x50 挑战 */
    private void onAuthCommand(int snEcho, byte[] params) {
        if (params == null || params.length != 17 || (params[0] & 0xFF) != 0x01) {
            trace("MmaClient", "auth cmd unexpected: "
                    + (params == null ? "null" : XiaomiAuth.toHex(params)));
            sendResponse(OP_AUTH, XiaomiProtocol.authApp(profile), snEcho, 0x07, new byte[0]);
            return;
        }
        byte[] deviceChallenge = new byte[16];
        System.arraycopy(params, 1, deviceChallenge, 0, 16);
        byte[] transform = XiaomiAuth.e21(deviceChallenge);
        byte[] data = new byte[17];
        data[0] = 0x01;
        System.arraycopy(transform, 0, data, 1, 16);
        sendResponse(OP_AUTH, XiaomiProtocol.authApp(profile), snEcho, 0x00, data);
    }

    private void markReady() {
        if (!transportOpen() || isConnected() || (profile != 1 && !authVerified)) return;
        ready();
        trace("MmaClient", "auth ready");
        queryBattery();
        queryNoise();
        confirmModelFeatures(true);
    }

    // ---- 控制命令 ----

    @Override
    public synchronized void queryBattery() {
        if (!isConnected()) return;
        sendCommand(OP_GET_TARGET_INFO, dataApp(),
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    }

    @Override
    public synchronized void queryNoise() {
        if (!isConnected() || noNoiseModel) return;
        sendCommand(OP_GET_CONFIG, dataApp(),
                new byte[]{0x00, 0x0A, 0x00, (byte) CFG_NOISE});
    }

    @Override
    public synchronized void changeNoise(int mode, Runnable flushed) {
        if (mode < 0 || mode > 2 || noNoiseModel || !noiseSupported) {
            reportError("该耳机尚未确认降噪能力"); return;
        }
        if (!isConnected()) return;
        Runnable done = () -> { if (flushed != null) flushed.run(); schedule(this::queryNoise, 350); };
        int w = wireMode(mode);
        if (profile != 0) {
            // Buds 5 / 3 Pro 的实测使用配置写；6 Lite 的相同配置写不会驱动 DSP。
            sendCommand(OP_SET_CONFIG, dataApp(),
                    XiaomiProtocol.configTlv(CFG_NOISE, new byte[]{(byte) w, (byte) noiseLevels[w]}), done);
        } else {
            sendCommand(OP_SET_TARGET_INFO, dataApp(), new byte[]{2, 4, (byte) w}, done);
        }
    }

    @Override public synchronized void querySpatial() { queryFeature(FeatureId.SPATIAL_AUDIO); }
    @Override public synchronized void setSpatial(boolean on) { writeFeature(FeatureId.SPATIAL_AUDIO, on ? 1 : 0, null); }
    @Override public synchronized void queryEqualizer() { queryFeature(FeatureId.EQUALIZER); }
    @Override public synchronized void setEqualizer(int preset) { writeFeature(FeatureId.EQUALIZER, preset, null); }

    @Override
    public synchronized void queryFeature(FeatureId id) {
        if (id == null || !isConnected()) return;
        switch (id) {
            case NOISE_MODE:
                queryNoise();
                break;
            case ANC_LEVEL:
                if (noNoiseModel || !modelCaps.ancLevels) return;
                if (modelCaps.adaptiveAncToggle) {
                    sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_NOISE, CFG_ADAPTIVE_ANC));
                } else {
                    sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_NOISE));
                }
                break;
            case TRANS_MODE:
                if (noNoiseModel || !modelCaps.transModes) return;
                sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_NOISE));
                break;
            case EQUALIZER:
                if (modelCaps.eqFamily == XiaomiProtocol.EQ_NONE) return;
                sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_EQUALIZER));
                break;
            case LOW_LATENCY:
                if (!modelCaps.lowLatency) return;
                sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_LOW_LATENCY));
                break;
            case MULTIPOINT:
                if (!modelCaps.multipoint) return;
                sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_MULTIPOINT));
                break;
            case WEAR_STATUS:
                if (!modelCaps.wearSensor) return;
                sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_WEAR_STATUS));
                break;
            case WEAR_DETECTION:
                if (!modelCaps.wearSensor) return;
                sendCommand(OP_GET_TARGET_INFO_EXT, dataApp(), new byte[]{0x0A});
                break;
            case SPATIAL_AUDIO:
            case HEAD_TRACKING:
                if (modelCaps.spatialKind == XiaomiProtocol.SPATIAL_HT_1D) {
                    sendCommand(OP_GET_CONFIG, dataApp(),
                            XiaomiProtocol.configQuery(CFG_SPATIAL_SET, CFG_SPATIAL_GET));
                } else if (id == FeatureId.SPATIAL_AUDIO
                        && modelCaps.spatialKind == XiaomiProtocol.SPATIAL_FIXED_4F) {
                    sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_SPATIAL_FIXED));
                }
                break;
            case SPATIAL_SCENE:
                if (modelCaps.spatialKind == XiaomiProtocol.SPATIAL_HT_1D) {
                    sendCommand(OP_GET_CONFIG, dataApp(), XiaomiProtocol.configQuery(CFG_SPATIAL_SCENE));
                }
                break;
            case FIRMWARE_INFO:
                queryBattery();
                break;
            default:
                break;
        }
    }

    @Override
    public synchronized void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (id == null || !isConnected() || !transportOpen()) return;
        if (id == FeatureId.NOISE_MODE) {
            changeNoise(value, flushed);
            return;
        }
        FeatureDescriptor spec = findDescriptor(id);
        if (spec == null || !spec.isValidValue(value)) {
            reportError("当前机型不支持该扩展控制");
            return;
        }
        switch (id) {
            case ANC_LEVEL:
                if (noNoiseModel || !modelCaps.ancLevels) return;
                if (modelCaps.adaptiveAncToggle && value == 3) {
                    sendCommand(OP_SET_CONFIG, dataApp(),
                            XiaomiProtocol.configTlv(CFG_ADAPTIVE_ANC, new byte[]{0x01}), flushed);
                } else {
                    if (modelCaps.adaptiveAncToggle && adaptiveAnc) {
                        sendCommand(OP_SET_CONFIG, dataApp(),
                                XiaomiProtocol.configTlv(CFG_ADAPTIVE_ANC, new byte[]{0x00}));
                    }
                    noiseLevels[1] = value;
                    sendCommand(OP_SET_CONFIG, dataApp(),
                            XiaomiProtocol.configTlv(CFG_NOISE, new byte[]{0x01, (byte) value}), flushed);
                }
                break;
            case TRANS_MODE:
                if (noNoiseModel || !modelCaps.transModes) return;
                noiseLevels[2] = value;
                sendCommand(OP_SET_CONFIG, dataApp(),
                        XiaomiProtocol.configTlv(CFG_NOISE, new byte[]{0x02, (byte) value}), flushed);
                break;
            case EQUALIZER:
                if (modelCaps.eqFamily == XiaomiProtocol.EQ_NONE) return;
                sendCommand(OP_SET_CONFIG, dataApp(),
                        XiaomiProtocol.configTlv(CFG_EQUALIZER, new byte[]{(byte) value}), flushed);
                break;
            case LOW_LATENCY:
                if (!modelCaps.lowLatency) return;
                sendCommand(OP_SET_CONFIG, dataApp(),
                        XiaomiProtocol.configTlv(CFG_LOW_LATENCY, new byte[]{(byte) (value != 0 ? 1 : 0)}), flushed);
                break;
            case MULTIPOINT:
                if (!modelCaps.multipoint) return;
                sendCommand(OP_SET_CONFIG, dataApp(),
                        XiaomiProtocol.configTlv(CFG_MULTIPOINT, new byte[]{(byte) (value != 0 ? 1 : 0)}), flushed);
                break;
            case WEAR_DETECTION:
                if (!modelCaps.wearSensor) return;
                // 反相布尔: 0x00 = 开启佩戴检测, 0x01 = 关闭佩戴检测
                byte wireWear = (byte) (value != 0 ? 0x00 : 0x01);
                sendCommand(OP_SET_TARGET_INFO, dataApp(), new byte[]{0x02, 0x06, wireWear}, flushed);
                break;
            case FIND_MY_BUDS:
                if (!modelCaps.findBuds) return;
                if (value > 0 && wearStatus > 0) {
                    reportError("检测到耳机佩戴在耳中，已阻止查找响铃");
                    return;
                }
                if (value == 0) {
                    pendingFindTarget = 0;
                    pendingFindSn = sendCommand(OP_SET_CONFIG, dataApp(),
                            XiaomiProtocol.configTlv(CFG_FIND_BUDS, new byte[]{0x00, 0x03}), flushed);
                } else {
                    sendCommand(OP_SET_CONFIG, dataApp(),
                            XiaomiProtocol.configTlv(CFG_FIND_BUDS, new byte[]{0x00, 0x03}));
                    pendingFindTarget = value;
                    pendingFindSn = sendCommand(OP_SET_CONFIG, dataApp(),
                            XiaomiProtocol.configTlv(CFG_FIND_BUDS, new byte[]{0x01, (byte) value}), flushed);
                }
                break;
            case SPATIAL_AUDIO:
                if (modelCaps.spatialKind == XiaomiProtocol.SPATIAL_HT_1D) {
                    byte mask = (byte) (value == 0 ? 0x02 : (headTrackingOn ? 0x0B : 0x03));
                    sendCommand(OP_SET_CONFIG, dataApp(),
                            XiaomiProtocol.configTlv(CFG_SPATIAL_SET, new byte[]{mask}), flushed);
                } else if (modelCaps.spatialKind == XiaomiProtocol.SPATIAL_FIXED_4F) {
                    sendCommand(OP_SET_CONFIG, dataApp(),
                            XiaomiProtocol.configTlv(CFG_SPATIAL_FIXED, new byte[]{(byte) (value != 0 ? 1 : 0)}), flushed);
                }
                break;
            case HEAD_TRACKING:
                if (modelCaps.spatialKind != XiaomiProtocol.SPATIAL_HT_1D) return;
                byte htMask = (byte) (value != 0 ? 0x0B : 0x03);
                sendCommand(OP_SET_CONFIG, dataApp(),
                        XiaomiProtocol.configTlv(CFG_SPATIAL_SET, new byte[]{htMask}), flushed);
                break;
            case SPATIAL_SCENE:
                if (modelCaps.spatialKind != XiaomiProtocol.SPATIAL_HT_1D) return;
                sendCommand(OP_SET_CONFIG, dataApp(),
                        XiaomiProtocol.configTlv(CFG_SPATIAL_SCENE, new byte[]{0x01, (byte) value}), flushed);
                break;
            default:
                break;
        }
    }

    // ---- 接收 ----

    @Override protected void received(byte[] data) {
        for (byte[] frame : decoder.feed(data, data.length)) {
            if (!transportOpen()) return;
            dispatchFrame(frame, 0, frame.length - 8);
        }
    }

    private void dispatchFrame(byte[] b, int head, int paramLen) {
        int flags = b[head + 3] & 0xFF;
        int opcode = b[head + 4] & 0xFF;
        boolean isCommand = (flags & 0x80) != 0;
        if (isCommand) {
            byte[] params = slice(b, head + 8, paramLen - 1);
            trace("MmaClient", "rx cmd op=0x" + Integer.toHexString(opcode)
                    + " sn=" + (b[head + 7] & 0xFF) + " params=" + XiaomiAuth.toHex(params));
            if (opcode == OP_AUTH) {
                onAuthCommand(b[head + 7] & 0xFF, params);
            } else if (opcode == OP_AUTH_DONE && params.length == 2 && params[0] == 1 && params[1] == 0) {
                sendResponse(OP_AUTH_DONE, XiaomiProtocol.authApp(profile), b[head + 7] & 0xFF, 0x00,
                        new byte[]{0x01});
                markReady();
            } else if (isConnected() && opcode == OP_NOTIFY_CONFIG) {
                parseConfig(params);
                if ((flags & 0x40) != 0) sendResponse(opcode, flags & 7, b[head + 7] & 255, 0, new byte[0]);
            } else if (isConnected() && (opcode == OP_PUSH_TARGET_INFO || opcode == OP_GET_TARGET_INFO_EXT)) {
                parseTargetInfo(params);
                if ((flags & 0x40) != 0) sendResponse(opcode, flags & 7, b[head + 7] & 255, 0, new byte[0]);
            } else {
                trace("MmaClient", "ignore cmd op=0x" + Integer.toHexString(opcode));
            }
            return;
        }
        int status = b[head + 7] & 0xFF;
        byte[] data = slice(b, head + 9, paramLen - 2);
        trace("MmaClient", "rx op=0x" + Integer.toHexString(opcode)
                + " status=" + status + " data=" + XiaomiAuth.toHex(data));
        int serial = b[head + 8] & 255;
        if (status != 0) {
            if ((opcode == OP_AUTH && serial == authSn) || (opcode == OP_AUTH_DONE && serial == authDoneSn)) {
                fail("耳机拒绝认证: " + status);
            } else if (isConnected() && (opcode == OP_SET_TARGET_INFO || opcode == OP_SET_CONFIG)) {
                if (opcode == OP_SET_CONFIG && serial == pendingFindSn) pendingFindSn = -1;
                reportError("耳机拒绝切换模式: " + status);
            }
            return;
        }
        if (opcode == OP_AUTH) {
            onAuthResponse(serial, data);
        } else if (opcode == OP_AUTH_DONE && serial == authDoneSn) {
            if (data.length == 0 || (data.length == 1 && data[0] == 1)) markReady();
        } else if ((isConnected() || profile == 1)
                && (opcode == OP_GET_TARGET_INFO || opcode == OP_GET_TARGET_INFO_EXT)) {
            parseTargetInfo(data);
        } else if ((isConnected() || profile == 1) && (opcode == OP_GET_CONFIG || opcode == OP_NOTIFY_CONFIG)) {
            parseConfig(data);
        } else if (isConnected() && opcode == OP_SET_CONFIG) {
            if (serial == pendingFindSn) {
                pendingFindSn = -1;
                findRingingTarget = pendingFindTarget;
                reportFeature(FeatureId.FIND_MY_BUDS, findRingingTarget);
            }
            if (data.length > 0) parseConfig(data);
        } else if (isConnected() && opcode == OP_SET_TARGET_INFO && data.length > 0) {
            parseTargetInfo(data);
        }
    }

    private static byte[] slice(byte[] b, int off, int len) {
        int n = Math.max(0, len);
        byte[] out = new byte[n];
        if (n > 0 && off + n <= b.length) System.arraycopy(b, off, out, 0, n);
        return out;
    }

    private void parseTargetInfo(byte[] data) {
        if (!transportOpen()) return;
        int[] vp = XiaomiProtocol.vidPid(data);
        if (vp != null) {
            XiaomiProtocol.ModelCaps byId = XiaomiProtocol.capsForVidPid(vp[0], vp[1]);
            if (byId != null && byId != modelCaps) {
                modelCaps = byId;
                profile = byId.profile;
                noNoiseModel = byId.noNoise;
                rebuildDescriptors();
                if (isConnected()) confirmModelFeatures(true);
            }
        }
        int[] battery = XiaomiProtocol.battery(data);
        if (battery != null && transportOpen()) {
            if (!isConnected() && profile == 1) markReady();
            events().onBattery(battery[0], battery[1], battery[2], battery[3]);
        }
        String fw = XiaomiProtocol.firmware(data);
        if (fw != null && !fw.isEmpty() && isConnected()) {
            FeatureDescriptor fwSpec = findDescriptor(FeatureId.FIRMWARE_INFO);
            if (fwSpec != null && !confirmedFeatures.contains(FeatureId.FIRMWARE_INFO)) {
                confirmedFeatures.add(FeatureId.FIRMWARE_INFO);
                confirmFeature(fwSpec);
            }
            reportFeatureText(FeatureId.FIRMWARE_INFO, fw);
        }
        Boolean wearDet = XiaomiProtocol.wearDetection(data);
        if (wearDet != null && modelCaps.wearSensor && isConnected()) {
            reportFeature(FeatureId.WEAR_DETECTION, wearDet.booleanValue() ? 1 : 0);
        }
    }

    private void parseConfig(byte[] data) {
        if (!transportOpen()) return;
        if (!noNoiseModel) {
            byte[] cycle = XiaomiProtocol.config(data, CFG_CYCLE);
            if (cycle != null && cycle.length == 2) {
                int mask = (cycle[0] | cycle[1]) & 255;
                if ((mask & ~7) == 0 && (mask & 6) == 6) noiseSupported = true;
            }
            int wire = XiaomiProtocol.noise(data);
            if (wire >= 0) {
                byte[] cfg = XiaomiProtocol.config(data, CFG_NOISE);
                if (cfg != null && cfg.length >= 2) {
                    int lvl = cfg[1] & 255;
                    noiseLevels[wire] = lvl;
                    lastWireMode = wire;
                    noiseSupported = true;
                    if (!isConnected() && profile == 1) markReady();
                    events().onNoiseMode(uiMode(wire));
                    if (wire == 1 && modelCaps.ancLevels && !adaptiveAnc) {
                        FeatureDescriptor ancSpec = findDescriptor(FeatureId.ANC_LEVEL);
                        if (ancSpec != null && ancSpec.isValidValue(lvl)) {
                            reportFeature(FeatureId.ANC_LEVEL, lvl);
                        }
                    } else if (wire == 2 && modelCaps.transModes) {
                        FeatureDescriptor transSpec = findDescriptor(FeatureId.TRANS_MODE);
                        if (transSpec != null && transSpec.isValidValue(lvl)) {
                            reportFeature(FeatureId.TRANS_MODE, lvl);
                        }
                    }
                }
            }
            if (modelCaps.adaptiveAncToggle) {
                byte[] adap = XiaomiProtocol.config(data, CFG_ADAPTIVE_ANC);
                if (adap != null && adap.length >= 1) {
                    adaptiveAnc = (adap[0] & 255) != 0;
                    if (adaptiveAnc) {
                        reportFeature(FeatureId.ANC_LEVEL, 3);
                    } else if (lastWireMode == 1) {
                        FeatureDescriptor ancSpec = findDescriptor(FeatureId.ANC_LEVEL);
                        if (ancSpec != null && ancSpec.isValidValue(noiseLevels[1])) {
                            reportFeature(FeatureId.ANC_LEVEL, noiseLevels[1]);
                        }
                    }
                }
            }
        }
        if (!isConnected()) return;
        if (modelCaps.eqFamily != XiaomiProtocol.EQ_NONE) {
            byte[] eqCfg = XiaomiProtocol.config(data, CFG_EQUALIZER);
            if (eqCfg != null && eqCfg.length >= 1) {
                int eq = eqCfg[0] & 255;
                FeatureDescriptor eqSpec = findDescriptor(FeatureId.EQUALIZER);
                if (eqSpec != null && eqSpec.isValidValue(eq)) {
                    equalizerConfirmed = true;
                    reportFeature(FeatureId.EQUALIZER, eq);
                    events().onEqualizer(eq);
                }
            }
        }
        if (modelCaps.lowLatency) {
            byte[] ll = XiaomiProtocol.config(data, CFG_LOW_LATENCY);
            if (ll != null && ll.length >= 1) {
                reportFeature(FeatureId.LOW_LATENCY, (ll[0] & 255) != 0 ? 1 : 0);
            }
        }
        if (modelCaps.multipoint) {
            byte[] mp = XiaomiProtocol.config(data, CFG_MULTIPOINT);
            if (mp != null && mp.length >= 1) {
                reportFeature(FeatureId.MULTIPOINT, (mp[0] & 255) != 0 ? 1 : 0);
            }
        }
        if (modelCaps.wearSensor) {
            int wear = XiaomiProtocol.wearStatus(data);
            if (wear >= 0) {
                wearStatus = wear;
                reportFeature(FeatureId.WEAR_STATUS, wear);
                if (wear > 0 && findRingingTarget > 0) {
                    findRingingTarget = 0;
                    pendingFindSn = -1;
                    sendCommand(OP_SET_CONFIG, dataApp(),
                            XiaomiProtocol.configTlv(CFG_FIND_BUDS, new byte[]{0x00, 0x03}));
                    reportFeature(FeatureId.FIND_MY_BUDS, 0);
                }
            }
        }
        if (modelCaps.findBuds) {
            byte[] fb = XiaomiProtocol.config(data, CFG_FIND_BUDS);
            if (fb != null && fb.length >= 2) {
                int st = fb[0] & 255;
                int mask = fb[1] & 255;
                int target = st == 0 ? 0 : ((mask >= 1 && mask <= 3) ? mask : 3);
                findRingingTarget = target;
                reportFeature(FeatureId.FIND_MY_BUDS, target);
            } else if (fb != null && fb.length == 1 && (fb[0] & 255) <= 3) {
                int target = fb[0] & 255;
                findRingingTarget = target;
                reportFeature(FeatureId.FIND_MY_BUDS, target);
            }
        }
        if (modelCaps.spatialKind == XiaomiProtocol.SPATIAL_HT_1D) {
            byte[] sp = XiaomiProtocol.config(data, CFG_SPATIAL_SET);
            if (sp == null) sp = XiaomiProtocol.config(data, CFG_SPATIAL_GET);
            if (sp != null && sp.length >= 1) {
                int v = sp[0] & 255;
                boolean spOn = (v & 0x01) != 0;
                boolean htOn = spOn && (v & 0x08) != 0;
                spatialOn = spOn;
                headTrackingOn = htOn;
                spatialConfirmed = true;
                reportFeature(FeatureId.SPATIAL_AUDIO, spOn ? 1 : 0);
                events().onSpatial(spOn);
                reportFeature(FeatureId.HEAD_TRACKING, htOn ? 1 : 0);
            }
            byte[] sc = XiaomiProtocol.config(data, CFG_SPATIAL_SCENE);
            if (sc != null) {
                int scene = sc.length >= 2 ? (sc[1] & 255) : (sc.length == 1 ? (sc[0] & 255) : -1);
                if (scene >= 1 && scene <= 5) {
                    reportFeature(FeatureId.SPATIAL_SCENE, scene);
                }
            }
        } else if (modelCaps.spatialKind == XiaomiProtocol.SPATIAL_FIXED_4F) {
            byte[] sp = XiaomiProtocol.config(data, CFG_SPATIAL_FIXED);
            if (sp != null && sp.length >= 1) {
                boolean spOn = (sp[0] & 255) != 0;
                spatialOn = spOn;
                spatialConfirmed = true;
                reportFeature(FeatureId.SPATIAL_AUDIO, spOn ? 1 : 0);
                events().onSpatial(spOn);
            }
        }
    }
    @Override public int potentialCaps() {
        return caps()
                | (noNoiseModel ? 0 : CAP_NOISE)
                | (modelCaps.spatialKind != XiaomiProtocol.SPATIAL_NONE ? CAP_SPATIAL : 0)
                | (modelCaps.eqFamily != XiaomiProtocol.EQ_NONE ? CAP_EQUALIZER : 0);
    }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            if (name == null) return false;
            String n = name.toLowerCase(java.util.Locale.ROOT);
            String model = n.replace("小米", "xiaomi").replaceAll("[^a-z0-9]", "");
            if (model.equals("xiaomibuds5pro")) return false;
            return n.contains("xiaomi") || n.contains("redmi") || n.contains("mibuds") || n.contains("红米") || n.contains("小米");
        }
        @Override public WireDriver create() { return new XiaomiMmaClient(); }
    }

}

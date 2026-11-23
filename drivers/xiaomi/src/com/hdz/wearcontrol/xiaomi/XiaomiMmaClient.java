package com.hdz.wearcontrol.xiaomi;


import com.hdz.wearcontrol.WireDriver;
import com.hdz.wearcontrol.Controls;

import java.security.SecureRandom;
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
 * 电量: GET_TARGET_INFO 0x02, params FF FF FF FF; 应答属性表 len|type|value,
 *   type 7 = [左, 右, 仓], bit7 充电, 低 7 位电量, FF 表示未知。
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
    private static final int OP_SET_TARGET_INFO = 0x08;
    private static final int OP_SET_CONFIG = 0xF2;
    private static final int OP_GET_CONFIG = 0xF3;
    private static final int OP_NOTIFY_CONFIG = 0xF4;
    private static final int OP_AUTH = 0x50;
    private static final int OP_AUTH_DONE = 0x51;

    private static final int ATTR_TYPE_BATTERY = 7;
    private static final int CFG_NOISE = 0x000B;
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
    private volatile boolean noiseSupported, noNoiseModel;
    private int profile;
    private final int[] noiseLevels = new int[3];
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
        authVerified = noiseSupported = false;
        authSn = authDoneSn = -1;
        challenge = null;
        decoder.reset();
        connectedName = deviceName();
        profile = XiaomiProtocol.profile(connectedName);
        java.util.Arrays.fill(noiseLevels, 0);
        String normalized = connectedName.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_-]", "");
        noNoiseModel = normalized.contains("buds6play") || normalized.contains("buds6active");
        sn = random.nextInt() & 0xFF;
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
        return CAP_BATTERY | (noiseSupported ? CAP_NOISE : 0);
    }

    @Override
    public int verifLevel() {
        return XiaomiProtocol.isVerifiedReal(connectedName) ? VERIF_REAL : VERIF_UNTESTED;
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
        Runnable done = () -> { flushed.run(); schedule(this::queryNoise, 350); };
        int w = wireMode(mode);
        if (profile != 0) {
            // Buds 5 / 3 Pro 的实测使用配置写；6 Lite 的相同配置写不会驱动 DSP。
            sendCommand(OP_SET_CONFIG, dataApp(), new byte[]{4, 0, 11, (byte) w, (byte) noiseLevels[w]}, done);
        } else {
            sendCommand(OP_SET_TARGET_INFO, dataApp(), new byte[]{2, 4, (byte) w}, done);
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
            } else if (isConnected() && opcode == 0x04) {
                parseBattery(params);
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
            } else if (isConnected() && (opcode == OP_SET_TARGET_INFO || opcode == OP_SET_CONFIG)) reportError("耳机拒绝切换模式: " + status);
            return;
        }
        if (opcode == OP_AUTH) {
            onAuthResponse(serial, data);
        } else if (opcode == OP_AUTH_DONE && serial == authDoneSn) {
            if (data.length == 0 || (data.length == 1 && data[0] == 1)) markReady();
        } else if ((isConnected() || profile == 1) && opcode == OP_GET_TARGET_INFO) {
            parseBattery(data);
        } else if ((isConnected() || profile == 1) && (opcode == OP_GET_CONFIG || opcode == OP_NOTIFY_CONFIG)) {
            parseConfig(data);
        }
    }

    private static byte[] slice(byte[] b, int off, int len) {
        int n = Math.max(0, len);
        byte[] out = new byte[n];
        if (n > 0 && off + n <= b.length) System.arraycopy(b, off, out, 0, n);
        return out;
    }

    private void parseBattery(byte[] data) {
        int[] battery = XiaomiProtocol.battery(data);
        if (battery == null || !transportOpen()) return;
        if (!isConnected() && profile == 1) markReady();
        events().onBattery(battery[0], battery[1], battery[2], battery[3]);
    }

    private void parseConfig(byte[] data) {
        if (noNoiseModel || !transportOpen()) return;
        byte[] cycle = XiaomiProtocol.config(data, 10);
        if (cycle != null && cycle.length == 2) {
            int mask = (cycle[0] | cycle[1]) & 255;
            if ((mask & ~7) == 0 && (mask & 6) == 6) noiseSupported = true;
        }
        int wire = XiaomiProtocol.noise(data);
        if (wire < 0) return;
        noiseLevels[wire] = XiaomiProtocol.config(data, CFG_NOISE)[1] & 255;
        if (profile != 0 || connectedName.toLowerCase(java.util.Locale.ROOT).contains("buds 6 lite")) noiseSupported = true;
        if (!isConnected() && profile == 1) markReady();
        if (noiseSupported) events().onNoiseMode(uiMode(wire));
    }
    @Override public int potentialCaps() { return caps() | (noNoiseModel ? 0 : CAP_NOISE); }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            String model = n.replace("小米", "xiaomi").replaceAll("[^a-z0-9]", "");
            if (model.equals("xiaomibuds5pro")) return false;
            return n.contains("xiaomi") || n.contains("redmi") || n.contains("mibuds") || n.contains("红米") || n.contains("小米");
        }
        @Override public WireDriver create() { return new XiaomiMmaClient(); }
    }

}

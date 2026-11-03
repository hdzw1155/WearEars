package com.hdz.wearcontrol.xiaomi;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hdz.wearcontrol.TwsClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
 *   耳机也可能主动发 0x50 挑战(需回 0x50 应答帧)。应答码比对失败只记日志, 照常放行。
 *
 * 电量: GET_TARGET_INFO 0x02, params FF FF FF FF; 应答属性表 len|type|value,
 *   type 7 = [左, 右, 仓], bit7 充电, 低 7 位电量, FF 表示未知。
 * 三态: SET_TARGET_INFO 0x08, params 02 04 mode(00 关/01 降噪/02 通透);
 *   状态用 GET_DEVICE_CONFIG 0xF3 [00 0B] 读回, 配置 TLV: len|type(2B)|value, id 000B = [mode, level]。
 *   注意: 写 config 11 不会真的切换声音, 不要用; 0x06 是断链命令, 绝对不要发。
 */
public class XiaomiMmaClient implements TwsClient {

    private static final UUID FAST_UUID =
            UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB");
    private static final UUID XIAOAI_UUID =
            UUID.fromString("00001101-0000-1000-8000-008584D01810");
    // SDP 解析不到时的固定信道兜底: Buds 6 Play = 6, Buds 6 Lite = 29
    private static final int[] RFCOMM_CHANNELS = {6, 29};

    private static final int APP_AUTH = 1;
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
    private static final int AUTH_TIMEOUT_MS = 15000;

    // UI 三态 ↔ 小米线值: 0=关 1=降噪 2=通透
    private static int wireMode(int uiMode) {
        if (uiMode == MODE_ANC) return 1;
        if (uiMode == MODE_TRANS) return 2;
        return 0;
    }

    private static int uiMode(int wireMode) {
        if (wireMode == 1) return MODE_ANC;
        if (wireMode == 2) return MODE_TRANS;
        return MODE_OFF;
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closing = false;
    private volatile boolean ready = false;
    private int sn;
    private byte[] challenge;
    private int pendingMode = -1;

    @Override
    public String brandName() {
        return "Xiaomi";
    }

    @Override
    public void init(Context ctx) {
    }

    @Override
    public int caps() {
        return CAP_NOISE | CAP_BATTERY;
    }

    @Override
    public int verifLevel() {
        return VERIF_UNTESTED;
    }

    // ---- 小米协议没有这些实验能力 ----

    @Override
    public void querySpatial() {
    }

    @Override
    public void setSpatial(boolean on) {
    }

    @Override
    public void queryDeepX() {
    }

    @Override
    public void setDeepX(int mode) {
    }

    @Override
    public void addListener(Listener l) {
        listeners.add(l);
    }

    @Override
    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    @Override
    public boolean isConnected() {
        BluetoothSocket s = socket;
        return s != null && s.isConnected();
    }

    // ---- 连接 ----

    @Override
    public void connect(final BluetoothDevice device) {
        closing = false;
        ready = false;
        pendingMode = -1;
        sn = random.nextInt() & 0xFF;
        new Thread(new Runnable() {
            @Override public void run() {
                BluetoothSocket s = null;
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        try {
                            adapter.cancelDiscovery();
                        } catch (Exception ignored) {
                        }
                    }
                    s = openSocket(device);
                    if (s == null) throw new IOException("找不到控制服务(通道 6/29)");
                    socket = s;
                    out = s.getOutputStream();
                } catch (Exception e) {
                    closeQuietly(s);
                    safeClose();
                    if (closing) return;
                    fireError("连接失败: " + e.getMessage());
                    return;
                }
                if (closing) {
                    safeClose();
                    return;
                }
                for (Listener l : listeners) l.onConnected(device.getName());
                startReader();
                startAuth();
                startAuthTimeout();
            }
        }, "mma-connect").start();
    }

    /** 先 SDP(fd2d), 再小爱 UUID, 最后固定信道兜底 */
    private BluetoothSocket openSocket(BluetoothDevice device) {
        BluetoothSocket s = null;
        try {
            s = device.createRfcommSocketToServiceRecord(FAST_UUID);
            s.connect();
            return s;
        } catch (Exception e) {
            closeQuietly(s);
            Log.d("MmaClient", "fd2d SDP fail: " + e);
        }
        try {
            s = device.createRfcommSocketToServiceRecord(XIAOAI_UUID);
            s.connect();
            return s;
        } catch (Exception e) {
            closeQuietly(s);
            Log.d("MmaClient", "xiaoai SDP fail: " + e);
        }
        for (int ch : RFCOMM_CHANNELS) {
            if (closing) return null;
            try {
                s = channelSocket(device, ch);
                s.connect();
                Log.d("MmaClient", "connected via channel " + ch);
                return s;
            } catch (Exception e) {
                closeQuietly(s);
                Log.d("MmaClient", "channel " + ch + " fail: " + e);
            }
        }
        return null;
    }

    private static BluetoothSocket channelSocket(BluetoothDevice device, int channel)
            throws Exception {
        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
        return (BluetoothSocket) m.invoke(device, channel);
    }

    @Override
    public void disconnect() {
        closing = true;
        main.removeCallbacksAndMessages(null);
        safeClose();
    }

    private void safeClose() {
        try {
            BluetoothSocket s = socket;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
        ready = false;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private void fireError(String msg) {
        for (Listener l : listeners) l.onError(msg);
    }

    /** 认证超时兜底: 15 秒没过认证就明说, 免得界面挂着“已连接”却永远没有数据 */
    private void startAuthTimeout() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(AUTH_TIMEOUT_MS);
                } catch (InterruptedException ignored) {
                }
                if (!closing && !ready) {
                    fireError("认证超时: 该机型可能不支持(实验性支持·未验证)");
                }
            }
        }, "mma-auth-timeout").start();
    }

    // ---- 帧 ----

    /** 命令帧: FE DC BA flags op len(2B BE) sn params EF; len = params.len + 1 */
    private synchronized void sendCommand(int opcode, int app, byte[] params) {
        OutputStream o = out;
        if (o == null) return;
        int paramsLen = params == null ? 0 : params.length;
        int len = paramsLen + 1;
        byte[] f = new byte[9 + paramsLen];
        int p = 0;
        f[p++] = (byte) 0xFE;
        f[p++] = (byte) 0xDC;
        f[p++] = (byte) 0xBA;
        f[p++] = (byte) (0x80 | 0x40 | (app & 0x07)); // 命令 + 要求应答 + app
        f[p++] = (byte) opcode;
        f[p++] = (byte) (len >> 8);
        f[p++] = (byte) len;
        f[p++] = (byte) sn;
        sn = (sn + 1) & 0xFF;
        if (paramsLen > 0) System.arraycopy(params, 0, f, p, paramsLen);
        f[f.length - 1] = (byte) 0xEF;
        writeRaw(o, f);
    }

    /** 应答帧: FE DC BA flags op len(2B BE) status sn data EF; len = data.len + 2 */
    private synchronized void sendResponse(int opcode, int app, int snEcho, int status, byte[] data) {
        OutputStream o = out;
        if (o == null) return;
        int dataLen = data == null ? 0 : data.length;
        int len = dataLen + 2;
        byte[] f = new byte[10 + dataLen];
        int p = 0;
        f[p++] = (byte) 0xFE;
        f[p++] = (byte) 0xDC;
        f[p++] = (byte) 0xBA;
        f[p++] = (byte) (app & 0x07); // 应答
        f[p++] = (byte) opcode;
        f[p++] = (byte) (len >> 8);
        f[p++] = (byte) len;
        f[p++] = (byte) status;
        f[p++] = (byte) snEcho;
        if (dataLen > 0) System.arraycopy(data, 0, f, p, dataLen);
        f[f.length - 1] = (byte) 0xEF;
        writeRaw(o, f);
    }

    private void writeRaw(OutputStream o, byte[] f) {
        try {
            o.write(f);
            o.flush();
            Log.d("MmaClient", "tx " + XiaomiAuth.toHex(f));
        } catch (IOException e) {
            fireError("发送失败: " + e.getMessage());
        }
    }

    // ---- 认证 ----

    private void startAuth() {
        challenge = new byte[16];
        random.nextBytes(challenge);
        byte[] params = new byte[17];
        params[0] = 0x01;
        System.arraycopy(challenge, 0, params, 1, 16);
        sendCommand(OP_AUTH, APP_AUTH, params);
    }

    private void onAuthResponse(byte[] data) {
        // 应答 data = 01 ‖ transform(16B); 比对失败只记日志, 不能中断认证
        if (data != null && data.length >= 17 && (data[0] & 0xFF) == 0x01) {
            byte[] expect = XiaomiAuth.e21(challenge);
            boolean ok = true;
            for (int i = 0; i < 16; i++) {
                if (expect[i] != data[1 + i]) {
                    ok = false;
                    break;
                }
            }
            Log.d("MmaClient", "auth transform " + (ok ? "matched" : "mismatched")
                    + " got=" + XiaomiAuth.toHex(data));
        } else {
            Log.d("MmaClient", "auth response unexpected: "
                    + (data == null ? "null" : XiaomiAuth.toHex(data)));
        }
        sendCommand(OP_AUTH_DONE, APP_AUTH, new byte[]{0x01, 0x00});
    }

    /** 耳机主动发起的 0x50 挑战 */
    private void onAuthCommand(int snEcho, byte[] params) {
        if (params == null || params.length < 17 || (params[0] & 0xFF) != 0x01) {
            Log.d("MmaClient", "auth cmd unexpected: "
                    + (params == null ? "null" : XiaomiAuth.toHex(params)));
            sendResponse(OP_AUTH, APP_AUTH, snEcho, 0x00, new byte[]{0x01});
            return;
        }
        byte[] deviceChallenge = new byte[16];
        System.arraycopy(params, 1, deviceChallenge, 0, 16);
        byte[] transform = XiaomiAuth.e21(deviceChallenge);
        byte[] data = new byte[17];
        data[0] = 0x01;
        System.arraycopy(transform, 0, data, 1, 16);
        sendResponse(OP_AUTH, APP_AUTH, snEcho, 0x00, data);
        sendCommand(OP_AUTH_DONE, APP_AUTH, new byte[]{0x01, 0x00});
    }

    private void markReady() {
        if (ready) return;
        ready = true;
        Log.d("MmaClient", "auth ready");
        queryBattery();
        queryNoise();
        int mode = pendingMode;
        pendingMode = -1;
        if (mode >= 0) setNoiseMode(mode);
    }

    // ---- 控制命令 ----

    @Override
    public void queryBattery() {
        if (!ready) return;
        sendCommand(OP_GET_TARGET_INFO, APP_DEVICE,
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    }

    @Override
    public void queryNoise() {
        if (!ready) return;
        sendCommand(OP_GET_CONFIG, APP_DEVICE,
                new byte[]{(byte) (CFG_NOISE >> 8), (byte) CFG_NOISE});
    }

    @Override
    public void setNoiseMode(int mode) {
        if (!ready) {
            pendingMode = mode; // 等认证完成后自动补发
            return;
        }
        int w = wireMode(mode);
        sendCommand(OP_SET_TARGET_INFO, APP_DEVICE,
                new byte[]{0x02, 0x04, (byte) w});
        // 写命令之后读回真实状态, 由 F3 回包驱动 onNoiseMode
        main.postDelayed(new Runnable() {
            @Override public void run() {
                queryNoise();
            }
        }, 350);
    }

    // ---- 接收 ----

    private void startReader() {
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] stage = new byte[2048];
                int stageLen = 0;
                byte[] chunk = new byte[512];
                try {
                    InputStream in = socket.getInputStream();
                    while (!closing) {
                        int r = in.read(chunk);
                        if (r < 0) break;
                        if (stageLen + r > stage.length) {
                            stageLen = 0;
                            continue;
                        }
                        System.arraycopy(chunk, 0, stage, stageLen, r);
                        stageLen += r;

                        int off = 0;
                        while (true) {
                            int head = findHeader(stage, off, stageLen);
                            if (head < 0) {
                                off = stageLen;
                                break;
                            }
                            if (head + 7 > stageLen) {
                                off = head;
                                break;
                            }
                            int paramLen = ((stage[head + 5] & 0xFF) << 8)
                                    | (stage[head + 6] & 0xFF);
                            int total = 7 + paramLen + 1;
                            if (paramLen < 2 || total > 1024) {
                                off = head + 1;
                                continue;
                            }
                            if (head + total > stageLen) {
                                off = head;
                                break;
                            }
                            if ((stage[head + total - 1] & 0xFF) == 0xEF) {
                                dispatchFrame(stage, head, paramLen);
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
                    if (!closing) {
                        for (Listener l : listeners) l.onDisconnected("连接已关闭");
                    }
                } catch (IOException e) {
                    if (!closing) {
                        for (Listener l : listeners) l.onDisconnected("连接断开: " + e.getMessage());
                    }
                }
                safeClose();
            }
        }, "mma-reader").start();
    }

    private static int findHeader(byte[] b, int from, int len) {
        for (int i = from; i + 3 <= len; i++) {
            if ((b[i] & 0xFF) == 0xFE && (b[i + 1] & 0xFF) == 0xDC
                    && (b[i + 2] & 0xFF) == 0xBA) {
                return i;
            }
        }
        return -1;
    }

    private void dispatchFrame(byte[] b, int head, int paramLen) {
        int flags = b[head + 3] & 0xFF;
        int opcode = b[head + 4] & 0xFF;
        boolean isCommand = (flags & 0x80) != 0;
        if (isCommand) {
            byte[] params = slice(b, head + 8, paramLen - 1);
            Log.d("MmaClient", "rx cmd op=0x" + Integer.toHexString(opcode)
                    + " sn=" + (b[head + 7] & 0xFF) + " params=" + XiaomiAuth.toHex(params));
            if (opcode == OP_AUTH) {
                onAuthCommand(b[head + 7] & 0xFF, params);
            } else if (opcode == OP_AUTH_DONE) {
                sendResponse(OP_AUTH_DONE, APP_AUTH, b[head + 7] & 0xFF, 0x00,
                        new byte[]{0x01});
                markReady();
            } else {
                Log.d("MmaClient", "ignore cmd op=0x" + Integer.toHexString(opcode));
            }
            return;
        }
        int status = b[head + 7] & 0xFF;
        byte[] data = slice(b, head + 9, paramLen - 2);
        Log.d("MmaClient", "rx op=0x" + Integer.toHexString(opcode)
                + " status=" + status + " data=" + XiaomiAuth.toHex(data));
        if (status != 0x00) return;
        if (opcode == OP_AUTH) {
            onAuthResponse(data);
        } else if (opcode == OP_AUTH_DONE) {
            markReady();
        } else if (opcode == OP_GET_TARGET_INFO) {
            parseBattery(data);
        } else if (opcode == OP_GET_CONFIG || opcode == OP_NOTIFY_CONFIG
                || opcode == OP_SET_CONFIG) {
            parseConfig(data);
        }
    }

    private static byte[] slice(byte[] b, int off, int len) {
        int n = Math.max(0, len);
        byte[] out = new byte[n];
        if (n > 0 && off + n <= b.length) System.arraycopy(b, off, out, 0, n);
        return out;
    }

    /** 电量属性表: len(u8)=1+valueLen | type(u8) | value; type 7 = [左, 右, 仓] */
    private void parseBattery(byte[] data) {
        int p = 0;
        int l = 0xFF, r = 0xFF, c = 0xFF;
        int flags = 0;
        while (p + 2 <= data.length) {
            int alen = data[p] & 0xFF;
            if (alen < 1 || p + 1 + alen > data.length) break;
            int type = data[p + 1] & 0xFF;
            int v = p + 2;
            int vlen = alen - 1;
            if (type == ATTR_TYPE_BATTERY && vlen >= 3) {
                l = data[v] & 0xFF;
                r = data[v + 1] & 0xFF;
                c = data[v + 2] & 0xFF;
            }
            p += 1 + alen;
        }
        if ((l & 0x80) != 0) flags |= 1;
        if ((r & 0x80) != 0) flags |= 2;
        if ((c & 0x80) != 0) flags |= 4;
        Log.d("MmaClient", "battery L/R/C=" + l + "/" + r + "/" + c + " flags=" + flags);
        for (Listener li : listeners) {
            li.onBattery(pct(l), pct(r), pct(c), flags);
        }
    }

    /** 配置 TLV: len(u8)=2+valueLen | type(u16 BE) | value; id 000B = [mode, level] */
    private void parseConfig(byte[] data) {
        int p = 0;
        while (p + 3 <= data.length) {
            int clen = data[p] & 0xFF;
            if (clen < 2 || p + 1 + clen > data.length) break;
            int type = ((data[p + 1] & 0xFF) << 8) | (data[p + 2] & 0xFF);
            int v = p + 3;
            int vlen = clen - 2;
            if (type == CFG_NOISE && vlen >= 1) {
                int wire = data[v] & 0xFF;
                if (wire == 0xFF) return; // 不支持
                Log.d("MmaClient", "noise wire=" + wire);
                for (Listener li : listeners) li.onNoiseMode(uiMode(wire));
                return;
            }
            p += 1 + clen;
        }
    }

    private static int pct(int b) {
        int p = b & 0x7F;
        return (p >= 1 && p <= 100) ? p : 0xFF;
    }
}

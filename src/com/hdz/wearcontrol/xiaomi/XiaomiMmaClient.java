package com.hdz.wearcontrol.xiaomi;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
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
 * 小米 / Redmi TWS 的 MMA 协议客户端(RFCOMM)。
 * 协议来源: TWS-Pods-PC xiaomi 目录(LineageOS Kotlin + MiBudsClient 实测交叉印证)。
 * 连接后须先过挑战-应答认证(XiaomiAuth, 36/36 真值验证), 才能查电量/切降噪。
 */
public class XiaomiMmaClient implements TwsClient {

    private static final UUID FAST_UUID =
            UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB");
    private static final UUID XIAOAI_UUID =
            UUID.fromString("00001101-0000-1000-8000-008584D01810");
    private static final int RFCOMM_CHANNEL = 6; // Redmi Buds 6 Play 实测兜底

    private static final int OP_GET_INFO = 0x02;
    private static final int OP_SET_CONFIG = 0xF2;
    private static final int OP_GET_CONFIG = 0xF3;
    private static final int OP_AUTH = 0x50;
    private static final int OP_AUTH_DONE = 0x51;
    private static final int MASK_BATTERY = 7;
    private static final int CFG_NOISE = 0x000B;

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
    private final SecureRandom random = new SecureRandom();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closing = false;
    private volatile boolean authed = false;
    private int sn;
    private byte[] challenge;

    @Override
    public String brandName() {
        return "Xiaomi";
    }

    /** 小米协议自包含, 不需要系统服务注入 */
    @Override
    public void init(Context ctx) {
    }

    @Override
    public int caps() {
        return CAP_NOISE | CAP_BATTERY;
    }

    /** 认证算法 36/36 真值验证过, 但整机链路未在任何真机上跑过 */
    @Override
    public int verifLevel() {
        return VERIF_UNTESTED;
    }

    // ---- 实验能力: 小米协议无这些命令, 空实现 ----

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
        return socket != null && socket.isConnected();
    }

    @Override
    public void connect(final BluetoothDevice device) {
        closing = false;
        authed = false;
        sn = random.nextInt() & 0xFF;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    try {
                        socket = device.createRfcommSocketToServiceRecord(FAST_UUID);
                        socket.connect();
                    } catch (IOException e) {
                        try {
                            if (socket != null) socket.close();
                        } catch (IOException ignored) {
                        }
                        // SDP 失败退小爱 UUID, 再退固定通道 6
                        try {
                            socket = device.createRfcommSocketToServiceRecord(XIAOAI_UUID);
                            socket.connect();
                        } catch (IOException e2) {
                            try {
                                if (socket != null) socket.close();
                            } catch (IOException ignored) {
                            }
                            socket = fallbackChannel(device, RFCOMM_CHANNEL);
                            socket.connect();
                        }
                    }
                    out = socket.getOutputStream();
                } catch (Exception e) {
                    safeClose();
                    if (closing) return; // 已被新连接替代,不再打扰 UI
                    fireError("连接失败: " + e.getMessage());
                    return;
                }
                if (closing) {
                    safeClose();
                    return;
                }
                for (Listener l : listeners) l.onConnected(device.getName());
                startAuth();
                startReader();
                startAuthTimeout();
            }
        }, "mma-connect").start();
    }

    /** 认证超时兜底: 6 秒没过认证就明说, 免得界面挂着"已连接"却永远没有数据 */
    private void startAuthTimeout() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException ignored) {
                }
                if (!closing && !authed) {
                    fireError("认证超时: 该机型可能不支持(实验性支持·未验证)");
                }
            }
        }, "mma-auth-timeout").start();
    }

    private static BluetoothSocket fallbackChannel(BluetoothDevice device, int channel)
            throws Exception {
        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
        return (BluetoothSocket) m.invoke(device, channel);
    }

    @Override
    public void disconnect() {
        closing = true;
        safeClose();
    }

    private void safeClose() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void fireError(String msg) {
        for (Listener l : listeners) l.onError(msg);
    }

    // ---- MMA 帧 ----

    /** Request: FE DC BA (0x80|0x40|ver) op len(2B BE) SN data EF */
    private synchronized void sendRequest(int opcode, byte[] data) {
        if (out == null) return;
        int length = (data == null ? 0 : data.length) + 1;
        byte[] f = new byte[3 + 1 + 1 + 2 + 1 + length + 1];
        int p = 0;
        f[p++] = (byte) 0xFE;
        f[p++] = (byte) 0xDC;
        f[p++] = (byte) 0xBA;
        f[p++] = (byte) (0x80 | 0x40 | 0x04); // Request|needReply|版本4(实测帧 0xC4)
        f[p++] = (byte) opcode;
        f[p++] = (byte) (length >> 8);
        f[p++] = (byte) length;
        f[p++] = (byte) sn;
        sn = (sn + 1) & 0xFF;
        if (data != null) System.arraycopy(data, 0, f, p, data.length);
        f[f.length - 1] = (byte) 0xEF;
        try {
            out.write(f);
            out.flush();
        } catch (IOException e) {
            fireError("发送失败: " + e.getMessage());
        }
    }

    // ---- 认证 ----

    private void startAuth() {
        challenge = new byte[16];
        random.nextBytes(challenge);
        byte[] data = new byte[17];
        data[0] = 0x01;
        System.arraycopy(challenge, 0, data, 1, 16);
        sendRequest(OP_AUTH, data);
    }

    private void onAuthResponse(byte[] data) {
        // 应答 data = 01 ‖ realCode(16B)
        if (data == null || data.length != 17 || (data[0] & 0xFF) != 0x01) {
            fireError("认证应答格式异常");
            return;
        }
        byte[] expect = XiaomiAuth.e21(challenge);
        for (int i = 0; i < 16; i++) {
            if (expect[i] != data[1 + i]) {
                fireError("认证失败: 耳机应答码不匹配");
                return;
            }
        }
        sendRequest(OP_AUTH_DONE, new byte[]{0x01, 0x00});
    }

    private void onAuthDone() {
        authed = true;
        queryBattery();
        queryNoise();
    }

    // ---- 控制命令 ----

    @Override
    public void queryBattery() {
        if (!authed) return;
        // data = 00 00 00 (1<<7) 即电量掩码
        sendRequest(OP_GET_INFO, new byte[]{0x00, 0x00, 0x00, (byte) (1 << MASK_BATTERY)});
    }

    @Override
    public void queryNoise() {
        if (!authed) return;
        sendRequest(OP_GET_CONFIG, new byte[]{(byte) (CFG_NOISE >> 8), (byte) CFG_NOISE});
    }

    @Override
    public void setNoiseMode(int mode) {
        if (!authed) {
            fireError("尚未通过认证");
            return;
        }
        // SET 配置: [len][id 2B][value...], 降噪 value = [mode, 00]
        int w = wireMode(mode);
        sendRequest(OP_SET_CONFIG,
                new byte[]{0x04, (byte) (CFG_NOISE >> 8), (byte) CFG_NOISE, (byte) w, 0x00});
    }

    // ---- 接收 ----

    private void startReader() {
        new Thread(new Runnable() {
            @Override public void run() {
                // 流式解码: 找帧头 FE DC BA, 拆完整帧
                try {
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[512];
                    int bufLen = 0;
                    while (!closing) {
                        int r = in.read(buf, bufLen, buf.length - bufLen);
                        if (r < 0) break;
                        bufLen += r;
                        int off = 0;
                        while (true) {
                            // 找帧头
                            int head = findHeader(buf, off, bufLen);
                            if (head < 0) {
                                // 没有帧头: 只留末尾 2 字节防帧头被截断, 其余丢弃
                                // (必须拷贝 buf[bufLen-keep..], 之前的写法从 off 拷贝会留错字节)
                                int keep = Math.min(2, bufLen);
                                if (keep > 0) {
                                    System.arraycopy(buf, bufLen - keep, buf, 0, keep);
                                }
                                bufLen = keep;
                                break;
                            }
                            if (head + 7 > bufLen) { // 头不完整
                                System.arraycopy(buf, head, buf, 0, bufLen - head);
                                bufLen -= head;
                                break;
                            }
                            int paramLen = ((buf[head + 5] & 0xFF) << 8) | (buf[head + 6] & 0xFF);
                            int total = 7 + paramLen + 1;
                            if (head + total > bufLen) { // 帧不完整
                                System.arraycopy(buf, head, buf, 0, bufLen - head);
                                bufLen -= head;
                                break;
                            }
                            if ((buf[head + total - 1] & 0xFF) == 0xEF) {
                                dispatchFrame(buf, head, paramLen);
                            }
                            off = head + total;
                            // 收尾搬移
                            if (off >= bufLen) {
                                bufLen = 0;
                                break;
                            }
                        }
                        if (bufLen == buf.length) bufLen = 0; // 防撑满
                    }
                    if (!closing) for (Listener l : listeners) l.onDisconnected("连接已关闭");
                } catch (IOException e) {
                    if (!closing) for (Listener l : listeners) l.onDisconnected("连接断开: " + e.getMessage());
                }
                safeClose();
            }
        }, "mma-reader").start();
    }

    private static int findHeader(byte[] b, int from, int len) {
        for (int i = from; i + 3 <= len; i++) {
            if ((b[i] & 0xFF) == 0xFE && (b[i + 1] & 0xFF) == 0xDC && (b[i + 2] & 0xFF) == 0xBA) {
                return i;
            }
        }
        return -1;
    }

    /** Response 帧: FE DC BA 00 op len(2B) status SN data EF */
    private void dispatchFrame(byte[] b, int head, int paramLen) {
        int typeByte = b[head + 3] & 0xFF;
        int opcode = b[head + 4] & 0xFF;
        int status = b[head + 7] & 0xFF;
        byte[] data = new byte[Math.max(0, paramLen - 2)];
        if (paramLen >= 2) System.arraycopy(b, head + 9, data, 0, paramLen - 2);
        Log.d("MmaClient", "rx op=0x" + Integer.toHexString(opcode)
                + " status=" + status + " data=" + XiaomiAuth.toHex(data));

        if ((typeByte & 0x80) != 0) return; // 耳机侧请求,忽略
        if (status != 0x00) return;

        if (opcode == OP_AUTH) {
            onAuthResponse(data);
        } else if (opcode == OP_AUTH_DONE) {
            onAuthDone();
        } else if (opcode == OP_GET_INFO && data.length >= 5 && (data[1] & 0xFF) == MASK_BATTERY) {
            // data = [len][mask][LL RR CC], 低 7 位百分比, bit7 充电中
            int l = data[2] & 0xFF, r = data[3] & 0xFF, c = data[4] & 0xFF;
            int flags = 0;
            if ((l & 0x80) != 0) flags |= 1;
            if ((r & 0x80) != 0) flags |= 2;
            if ((c & 0x80) != 0) flags |= 4;
            for (Listener li : listeners)
                li.onBattery(pct(l), pct(r), pct(c), flags);
        } else if (opcode == OP_GET_CONFIG || opcode == OP_SET_CONFIG) {
            // 配置回包 data = [len][id 2B][value...]
            if (data.length >= 4) {
                int id = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                if (id == CFG_NOISE && data.length >= 4) {
                    int wire = data[3] & 0xFF;
                    if (wire == 0xFF) return; // 不支持
                    for (Listener li : listeners) li.onNoiseMode(uiMode(wire));
                }
            }
        }
    }

    private static int pct(int b) {
        int p = b & 0x7F;
        return (p >= 1 && p <= 100) ? p : 0xFF;
    }
}

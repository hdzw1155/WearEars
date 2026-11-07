package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 原道(NiceHCK / OriG in)客户端(实验性).
 *
 * 协议依据: ZaeXT/NiceHCK_Controller(Java, MIT) + HELPMEEADICE/NiceHCK_Controller_Windows(Rust), 两边常量一致.
 * 帧: 4E | len_lo len_hi | 00 | op_lo op_hi | params...
 *   len = 3 + params 长度(u16 小端, 只算 len 之后的字节, 含 00+op); 总长 = len + 3; 无校验.
 * 只做最小集: 电量(0x0005) + 降噪查询(0x0101)/设置(0x0201) + 100ms 后回读确认.
 * 不走 BLE, 纯 SPP; 通道号不写死, 交给系统 SDP(createRfcommSocketToServiceRecord).
 */
public class NiceHckClient implements TwsClient {

    private static final String TAG = "NiceHck";

    /** 尾部 ASCII "NHCKCTRL" */
    private static final UUID SPP_UUID = UUID.fromString("0000a100-1000-8000-4e48-434b4354524c");
    private static final UUID SPP_UUID_STD = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private static final int OP_BATTERY = 0x0005;
    private static final int OP_ANC_QUERY = 0x0101;
    private static final int OP_ANC_SET = 0x0201;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ProtocolWriter writer = new ProtocolWriter();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final NiceHckProtocol decoder = new NiceHckProtocol();

    private Context app;
    private BluetoothDevice device;
    private volatile boolean closing;
    private volatile boolean connected;
    private volatile boolean noiseConfirmed;
    /** 当前连接设备名, 用于按型号如实标注验证等级 */
    private volatile String connectedName = "";
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private Thread worker;

    private int[] batt = {-1, -1, -1};
    private int lastMode = -1;

    private final Runnable confirm = new Runnable() {
        @Override public void run() {
            if (connected) {
                sendFrame(OP_ANC_QUERY, new byte[0]);
                sendFrame(OP_BATTERY, new byte[0]);
            }
        }
    };

    @Override public void init(Context ctx) {
        app = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override public void connect(BluetoothDevice target) {
        disconnect();
        writer.start();
        device = target;
        connectedName = target != null && target.getName() != null ? target.getName() : "";
        closing = false;
        connected = false;
        noiseConfirmed = false;
        batt[0] = -1;
        batt[1] = -1;
        batt[2] = -1;
        lastMode = -1;
        closeSocket();
        decoder.reset();
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!openSpp()) {
                        if (closing) return;
                        if (!openSppStd()) {
                            fail("连不上原道控制通道(SPP 没开?)");
                            return;
                        }
                    }
                    if (closing) { closeSocket(); return; }
                    connected = true;
                    postConnected();
                    // 连上先问一轮: 电量 + 当前降噪态
                    sendFrame(OP_BATTERY, new byte[0]);
                    h.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (connected) sendFrame(OP_ANC_QUERY, new byte[0]);
                        }
                    }, 120);
                    readLoop();
                } catch (Throwable t) {
                    Log.e(TAG, "connect failed", t);
                    fail("连接失败: " + t.getClass().getSimpleName());
                }
            }
        }, "nicehck-conn");
        worker.start();
    }

    private boolean openSpp() {
        return openSocket(SPP_UUID);
    }

    private boolean openSppStd() {
        return openSocket(SPP_UUID_STD);
    }

    private boolean openSocket(UUID uuid) {
        if (closing) return false;
        try {
            socket = device.createRfcommSocketToServiceRecord(uuid);
            if (closing) { closeSocket(); return false; }
            socket.connect();
            if (closing) { closeSocket(); return false; }
            out = socket.getOutputStream();
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "spp " + uuid + " failed: " + t.getMessage());
            try {
                if (socket != null) socket.close();
            } catch (Throwable ignored) {
            }
            socket = null;
            return false;
        }
    }

    private void readLoop() {
        final BluetoothSocket source = socket;
        if (closing || source == null) return;
        try {
            InputStream in = source.getInputStream();
            byte[] buf = new byte[512];
            while (!closing && socket == source) {
                int n = in.read(buf);
                if (closing || socket != source) break;
                if (n <= 0) break;
                feed(buf, n);
            }
        } catch (Throwable t) {
            Log.d(TAG, "read loop end: " + t.getMessage());
        }
        if (!closing && socket == source) {
            connected = false;
            h.post(new Runnable() {
                @Override public void run() {
                    if (!closing && socket == source) { disconnect(); fireDisconnected("耳机已断开"); }
                }
            });
        }
    }

    private void feed(byte[] data, int len) {
        for (byte[] frame : decoder.feed(data, len)) {
            if (closing) return;
            onFrame(frame, 0);
        }
    }

    private void onFrame(byte[] b, int i) {
        int flen = (b[i + 1] & 0xFF) | ((b[i + 2] & 0xFF) << 8);
        int op = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8);
        int payloadLen = flen + 3 - 6;
        if (payloadLen < 0) return;
        try {
            if (op == OP_BATTERY && payloadLen >= 3) {
                int l = b[i + 6] & 0xFF;
                int r = b[i + 7] & 0xFF;
                int c = b[i + 8] & 0xFF;
                deliverBattery(l, r, c);
            } else if (op == OP_ANC_QUERY && payloadLen >= 1) {
                int wire = b[i + 6] & 0xFF;
                if (wire <= 3 || wire == 0x10 || wire == 0x11) {
                    noiseConfirmed = true;
                    deliverMode(mapMode(wire));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse frame failed", t);
        }
    }

    /** 原道映射: 0=关 1=通透 2=普通降噪 3=深度降噪 0x10=试验性 0x11=风噪 */
    private static int mapMode(int v) {
        if (v == 0) return MODE_OFF;
        if (v == 1) return MODE_TRANS;
        if (v == 2 || v == 3) return MODE_ANC;
        return -1; // 试验/风噪/未知: 三态 UI 不点亮
    }

    private int toDeviceMode(int mode) {
        if (mode == MODE_OFF) return 0;
        if (mode == MODE_TRANS) return 1;
        if (mode == MODE_ANC) return 2;
        return -1;
    }

    private synchronized void sendFrame(int op, byte[] params) {
        if (!connected || out == null) return;
        int len = 3 + params.length;
        final byte[] f = new byte[len + 3];
        f[0] = 0x4E;
        f[1] = (byte) (len & 0xFF);
        f[2] = (byte) ((len >> 8) & 0xFF);
        f[3] = 0x00;
        f[4] = (byte) (op & 0xFF);
        f[5] = (byte) ((op >> 8) & 0xFF);
        if (params.length > 0) System.arraycopy(params, 0, f, 6, params.length);
        final OutputStream stream = out;
        if (!writer.post(new Runnable() {
            @Override public void run() {
                if (closing || !connected || stream != out) return;
                try { stream.write(f); stream.flush(); }
                catch (Exception e) { if (!closing && stream == out) fail("发送失败: " + e.getMessage()); }
            }
        }) && !closing) fireError("控制指令过多，请稍后重试");
    }

    private void deliverBattery(int l, int r, int c) {
        // 0 = 未知/无, 原样存着由渲染层显示 --
        batt[0] = l;
        batt[1] = r;
        batt[2] = c;
        for (Listener li : snapshot()) {
            try {
                li.onBattery(l, r, c, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    private void deliverMode(int mode) {
        lastMode = mode;
        for (Listener li : snapshot()) {
            try {
                li.onNoiseMode(mode);
            } catch (Throwable ignored) {
            }
        }
    }

    private void postConnected() {
        h.post(new Runnable() {
            @Override public void run() {
                if (closing || !connected) return;
                fireConnected(device != null && device.getName() != null ? device.getName() : "原道耳机");
                h.removeCallbacks(confirm);
            }
        });
    }

    private void fail(final String msg) {
        if (closing) return;
        Log.d(TAG, msg);
        final boolean wasConnected = connected;
        Runnable r = new Runnable() {
            @Override public void run() {
                if (closing) return;
                disconnect();
                if (!wasConnected) fireError(msg);
                else fireDisconnected(msg);
            }
        };
        // 契约 6.5: 连接失败先等耳机侧槽位释放(1.5s)再回调, 避免连-断循环
        if (wasConnected) h.post(r);
        else h.postDelayed(r, 1500);
    }

    private void closeSocket() {
        connected = false;
        h.removeCallbacks(confirm);
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
        socket = null;
        out = null;
    }

    @Override public void disconnect() {
        closing = true;
        writer.stop();
        h.removeCallbacksAndMessages(null);
        closeSocket();
    }

    @Override public boolean isConnected() {
        return connected;
    }

    @Override public void addListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    @Override public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private Listener[] snapshot() {
        synchronized (listeners) {
            return listeners.toArray(new Listener[0]);
        }
    }

    private void fireConnected(final String name) {
        for (Listener l : snapshot()) {
            try {
                l.onConnected(name);
            } catch (Throwable ignored) {
            }
        }
    }

    private void fireDisconnected(final String reason) {
        for (Listener l : snapshot()) {
            try {
                l.onDisconnected(reason);
            } catch (Throwable ignored) {
            }
        }
    }

    private void fireError(final String msg) {
        for (Listener l : snapshot()) {
            try {
                l.onError(msg);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override public void queryBattery() {
        sendFrame(OP_BATTERY, new byte[0]);
    }

    @Override public void queryNoise() {
        sendFrame(OP_ANC_QUERY, new byte[0]);
    }

    @Override public void setNoiseMode(int mode) {
        int v = toDeviceMode(mode);
        if (v < 0) return;
        sendFrame(OP_ANC_SET, new byte[]{(byte) v, 0x00});
        h.removeCallbacks(confirm);
        h.postDelayed(confirm, 150);
    }

    @Override public void querySpatial() {
    }

    @Override public void setSpatial(boolean on) {
    }

    @Override public void queryDeepX() {
    }

    @Override public void setDeepX(int mode) {
    }

    @Override public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0);
    }

    @Override public int verifLevel() {
        // 原道 OriG in(原点) 已在本项目真机验证(电量 + 降噪/通透/关闭三态);
        // 其他原道型号沿用同一 4E 帧协议, 但未逐型号实测, 如实标实验性。
        String n = connectedName == null ? "" : connectedName.toLowerCase();
        if (n.contains("orig") || n.contains("原点")) {
            return VERIF_REAL;
        }
        return VERIF_UNTESTED;
    }

    @Override public String brandName() {
        return "原道";
    }
}

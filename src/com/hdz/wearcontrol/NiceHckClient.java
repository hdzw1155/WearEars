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
import java.util.Arrays;
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
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final List<byte[]> rx = new ArrayList<byte[]>();

    private Context app;
    private BluetoothDevice device;
    private volatile boolean closing;
    private volatile boolean connected;
    private BluetoothSocket socket;
    private OutputStream out;
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
        device = target;
        closing = false;
        connected = false;
        batt[0] = -1;
        batt[1] = -1;
        batt[2] = -1;
        lastMode = -1;
        closeSocket();
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!openSpp()) {
                        if (!openSppStd()) {
                            fail("连不上原道控制通道(SPP 没开?)");
                            return;
                        }
                    }
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
        try {
            socket = device.createRfcommSocketToServiceRecord(uuid);
            socket.connect();
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
        try {
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[512];
            while (!closing) {
                int n = in.read(buf);
                if (n <= 0) break;
                feed(buf, n);
            }
        } catch (Throwable t) {
            Log.d(TAG, "read loop end: " + t.getMessage());
        }
        if (!closing) {
            connected = false;
            h.post(new Runnable() {
                @Override public void run() {
                    fireDisconnected("耳机已断开");
                }
            });
        }
    }

    private void feed(byte[] data, int len) {
        synchronized (rx) {
            rx.add(Arrays.copyOfRange(data, 0, len));
        }
        parse();
    }

    private void parse() {
        synchronized (rx) {
            if (rx.isEmpty()) return;
            int total = 0;
            for (byte[] b : rx) total += b.length;
            byte[] buf = new byte[total];
            int p = 0;
            for (byte[] b : rx) {
                System.arraycopy(b, 0, buf, p, b.length);
                p += b.length;
            }
            int i = 0;
            int consumed = 0;
            while (i + 2 < buf.length) {
                if (buf[i] != 0x4E) {
                    i++;
                    continue;
                }
                int flen = (buf[i + 1] & 0xFF) | ((buf[i + 2] & 0xFF) << 8);
                if (flen < 3 || flen > 4096) {
                    i++;
                    continue;
                }
                int need = flen + 3;
                if (i + need > buf.length) break; // 等后续分片
                onFrame(buf, i);
                i += need;
                consumed = i;
            }
            // 把已解析的字节丢掉, 保留残余
            rx.clear();
            if (consumed < buf.length) {
                rx.add(Arrays.copyOfRange(buf, consumed, buf.length));
            }
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
                deliverMode(mapMode(b[i + 6] & 0xFF));
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
        byte[] f = new byte[len + 3];
        f[0] = 0x4E;
        f[1] = (byte) (len & 0xFF);
        f[2] = (byte) ((len >> 8) & 0xFF);
        f[3] = 0x00;
        f[4] = (byte) (op & 0xFF);
        f[5] = (byte) ((op >> 8) & 0xFF);
        if (params.length > 0) System.arraycopy(params, 0, f, 6, params.length);
        try {
            out.write(f);
            out.flush();
        } catch (Throwable t) {
            fail("发送失败: " + t.getMessage());
        }
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
        if (mode < 0) return;
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
                fireConnected(device != null && device.getName() != null ? device.getName() : "原道耳机");
                h.removeCallbacks(confirm);
            }
        });
    }

    private void fail(final String msg) {
        Log.d(TAG, msg);
        final boolean wasConnected = connected;
        Runnable r = new Runnable() {
            @Override public void run() {
                if (!wasConnected) fireError(msg);
                else fireDisconnected(msg);
                closeSocket();
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
        return CAP_BATTERY | CAP_NOISE;
    }

    @Override public int verifLevel() {
        return VERIF_UNTESTED;
    }

    @Override public String brandName() {
        return "原道";
    }
}

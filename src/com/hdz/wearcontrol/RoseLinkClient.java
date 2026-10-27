package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
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
 * 弱水时砂 RoseLink 客户端(实验性).
 *
 * 协议依据: IRDecoyFlare/roseselsa-RoseLink-Protocol(逆向文档) + OPerepadia/rosebuds(MIT, Ceramics U 实机).
 * 只做最小集: 连上 → 全量 getInfo → 解析 0C 电量 / 09 降噪态 → 写 09 → 回读确认.
 * 不支持 EQ/游戏/空间音频/双设备/固件, 一条多余指令都不发.
 *
 * 传输: 先试 SPP(UUID 0cf12d31-…, 由系统 SDP 解析通道号, 不写死),
 * 再试 BLE GATT(服务 00FE, 写 00F1, 通知 00F2) —— 原版文档里的 L2CAP ATT PSM31 在 Android 公开 API 里没有,
 * 只能祈祷耳机也把 GATT 挂在 LE 侧. 两条路都不通就如实报错, 不反复骚扰用户.
 *
 * 帧族: V2(FF SEQ LEN BODY CS AA) 为主; RS Common(FF 00 LEN BODY AA) 兜底.
 * F8/ZT 族暂不实现(EQ 之类也没做), 遇到只会"连不上", 不影响其它品牌.
 */
public class RoseLinkClient implements TwsClient {

    private static final String TAG = "RoseLink";

    private static final UUID SPP_UUID = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3931");
    private static final UUID BLE_SERVICE = UUID.fromString("000000fe-0000-1000-8000-00805f9b34fb");
    private static final UUID BLE_CHAR_WRITE = UUID.fromString("000000f1-0000-1000-8000-00805f9b34fb");
    private static final UUID BLE_CHAR_NOTIFY = UUID.fromString("000000f2-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // V2 全量查询的 30 个参数 id
    private static final byte[] QUERY_IDS = {
            0x01, 0x07, 0x08, 0x09, 0x0C, 0x0D, 0x0E, 0x12, 0x2A, 0x2B,
            0x2C, 0x2D, 0x2E, 0x2F, 0x31, 0x32, 0x33, 0x36, 0x37, 0x38,
            0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3F, 0x45, 0x46, 0x49, (byte) 0xE9,
    };

    private static final int FAMILY_V2 = 0;
    private static final int FAMILY_RS = 1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final List<byte[]> rx = new ArrayList<byte[]>();

    private Context app;
    private BluetoothDevice device;
    private Thread worker;
    private volatile boolean closing;
    private volatile int family = FAMILY_V2;
    private int seq = 0;

    private OutputStream sppOut;
    private BluetoothSocket sppSocket;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;

    private boolean connected;
    private boolean gotFirst;
    private int lastMode = -1;
    private int[] batt = {-1, -1, -1};
    private int chargeFlags = 0;

    private final Runnable replyTimeout = new Runnable() {
        @Override public void run() {
            if (!connected || gotFirst || closing) return;
            // 首帧迟迟不来: 换一帧 RS Common 格式再试一次(V2 序列号/校验对不上时常见)
            if (family == FAMILY_V2) {
                family = FAMILY_RS;
                Log.d(TAG, "no V2 reply, try RS Common frame");
                sendQuery();
                h.postDelayed(this, 1500);
            } else {
                fail("耳机没回 RoseLink 查询(可能是 F8/ZT 型号, 暂未支持)");
            }
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!connected) return;
            sendQuery();
            h.postDelayed(this, 45000);
        }
    };

    @Override public void init(Context ctx) {
        app = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override public void connect(BluetoothDevice target) {
        device = target;
        closing = false;
        connected = false;
        gotFirst = false;
        family = FAMILY_V2;
        seq = 0;
        lastMode = -1;
        batt[0] = -1;
        batt[1] = -1;
        batt[2] = -1;
        chargeFlags = 0;
        disconnectInternal();
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!sppConnect()) {
                        if (!bleConnect()) {
                            fail("连不上 RoseLink 通道(可能该型号只开放私有 GATT)");
                            return;
                        }
                    }
                    h.post(new Runnable() {
                        @Override public void run() {
                            markConnected();
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "connect failed", t);
                    fail("连接失败: " + t.getClass().getSimpleName());
                }
            }
        }, "rose-conn");
        worker.start();
    }

    // ---- SPP ----

    private boolean sppConnect() {
        try {
            sppSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            sppSocket.connect();
            sppOut = sppSocket.getOutputStream();
            InputStream in = sppSocket.getInputStream();
            sendQuery();
            // 读循环占住本线程
            byte[] buf = new byte[256];
            while (!closing) {
                int n = in.read(buf);
                if (n <= 0) break;
                feed(buf, n, false);
            }
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "spp failed: " + t.getMessage());
            closeQuietly(sppSocket);
            sppSocket = null;
            return false;
        }
    }

    // ---- BLE GATT ----

    private boolean bleConnect() {
        final boolean[] ok = {false};
        final Object lock = new Object();
        if (app == null || device == null) return false;
        try {
            gatt = device.connectGatt(app, false, new GattCb(lock, ok), BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            Log.d(TAG, "connectGatt failed: " + t.getMessage());
            return false;
        }
        if (gatt == null) return false;
        synchronized (lock) {
            try {
                lock.wait(5000);
            } catch (InterruptedException ignored) {
            }
        }
        if (!ok[0]) {
            try {
                if (gatt != null) gatt.close();
            } catch (Throwable ignored) {
            }
            gatt = null;
            return false;
        }
        return true;
    }

    private class GattCb extends BluetoothGattCallback {
        private final Object lock;
        private final boolean[] ok;

        GattCb(Object lock, boolean[] ok) {
            this.lock = lock;
            this.ok = ok;
        }

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && connected) {
                connected = false;
                h.removeCallbacks(heartbeat);
                fireDisconnected("耳机已断开");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattCharacteristic notifyChar = null;
            try {
                if (g.getService(BLE_SERVICE) == null) {
                    failLater("BLE 服务 00FE 不存在");
                    return;
                }
                writeChar = g.getService(BLE_SERVICE).getCharacteristic(BLE_CHAR_WRITE);
                notifyChar = g.getService(BLE_SERVICE).getCharacteristic(BLE_CHAR_NOTIFY);
                if (writeChar == null || notifyChar == null) {
                    failLater("BLE 特征 F1/F2 缺失");
                    return;
                }
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
                synchronized (lock) {
                    ok[0] = true;
                    lock.notifyAll();
                }
            } catch (Throwable t) {
                failLater("BLE 初始化失败: " + t.getMessage());
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            // 通知开关打开后再发查询(原版顺序: 先开接收再查询, 允许设备主动推)
            sendQuery();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null) feed(v, v.length, true);
        }
    }

    // ---- 发送 ----

    private void sendQuery() {
        if (family == FAMILY_V2) {
            byte[] body = new byte[1 + QUERY_IDS.length];
            body[0] = (byte) 0xFA;
            System.arraycopy(QUERY_IDS, 0, body, 1, QUERY_IDS.length);
            sendV2(body);
        } else {
            // RS Common: FF 00 LEN BODY AA, body 以 FA 开头, 无 CS/SEQ
            byte[] body = new byte[1 + QUERY_IDS.length];
            body[0] = (byte) 0xFA;
            System.arraycopy(QUERY_IDS, 0, body, 1, QUERY_IDS.length);
            byte[] frame = new byte[3 + body.length + 1];
            frame[0] = (byte) 0xFF;
            frame[1] = 0x00;
            frame[2] = (byte) body.length;
            System.arraycopy(body, 0, frame, 3, body.length);
            frame[frame.length - 1] = (byte) 0xAA;
            sendRaw(frame);
        }
    }

    private void sendV2(byte[] body) {
        // FF SEQ LEN BODY CS AA
        byte[] frame = new byte[4 + body.length + 1];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) (seq++ & 0xFF);
        frame[2] = (byte) body.length;
        System.arraycopy(body, 0, frame, 3, body.length);
        int cs = 0;
        for (int i = 0; i < frame.length - 1; i++) cs = (cs + (frame[i] & 0xFF)) & 0xFF;
        frame[frame.length - 1] = (byte) cs;
        // 末尾补 AA: frame 当前是 FF SEQ LEN BODY CS, 再补 AA
        byte[] full = new byte[frame.length + 1];
        System.arraycopy(frame, 0, full, 0, frame.length);
        full[full.length - 1] = (byte) 0xAA;
        sendRaw(full);
    }

    private void sendNoise(int mode) {
        int v;
        if (mode == MODE_ANC) v = 1;
        else if (mode == MODE_OFF) v = 2;
        else if (mode == MODE_TRANS) v = 3;
        else return;
        if (family == FAMILY_V2) {
            sendV2(new byte[]{(byte) 0x09, (byte) v});
        } else {
            sendRaw(new byte[]{(byte) 0xFF, 0x00, 0x02, (byte) 0x09, (byte) v, (byte) 0xAA});
        }
        // 设备约 0.5s 后才生效: 稍后回读确认, 别拿旧状态糊弄 UI
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (connected) sendQuery();
            }
        }, 700);
    }

    private synchronized void sendRaw(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        try {
            if (sppOut != null) {
                sppOut.write(frame);
                sppOut.flush();
            } else if (gatt != null && writeChar != null) {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                writeChar.setValue(frame);
                gatt.writeCharacteristic(writeChar);
            }
        } catch (Throwable t) {
            fail("发送失败: " + t.getMessage());
        }
    }

    // ---- 接收解析 ----

    /** 把任意分片喂进缓冲, 逐帧解析; 收到首帧后再也不管"没回包"计时 */
    private void feed(byte[] data, int len, boolean ble) {
        synchronized (rx) {
            rx.add(java.util.Arrays.copyOfRange(data, 0, len));
        }
        byte[] merged = drain();
        if (merged == null || merged.length == 0) return;
        parse(merged);
    }

    private synchronized byte[] drain() {
        synchronized (rx) {
            if (rx.isEmpty()) return null;
            int total = 0;
            for (byte[] b : rx) total += b.length;
            byte[] out = new byte[total];
            int p = 0;
            for (byte[] b : rx) {
                System.arraycopy(b, 0, out, p, b.length);
                p += b.length;
            }
            rx.clear();
            return out;
        }
    }

    private void parse(byte[] b) {
        int i = 0;
        while (i < b.length) {
            if (b[i] == (byte) 0xDD && i + 3 < b.length) {
                if (b[i + 1] == 0x00) {
                    // RS Common: DD 00 LEN TYPE VALUE... AA
                    int len = b[i + 2] & 0xFF;
                    int total = len + 4;
                    if (i + total > b.length) return; // 等后续分片
                    onFrame(b, i, total, FAMILY_RS);
                    i += total;
                } else {
                    // V2: DD SEQ [L TYPE VALUE...] CS AA
                    int end = -1;
                    for (int e = i + 2; e + 1 < b.length; e++) {
                        if (b[e + 1] == (byte) 0xAA && (sum(b, i, e) & 0xFF) == (b[e] & 0xFF)) {
                            end = e;
                            break;
                        }
                    }
                    if (end < 0) return; // 不完整
                    onFrame(b, i, end - i + 2, FAMILY_V2);
                    i = end + 2;
                }
            } else if (b[i] == (byte) 0xFF) {
                // 回包偶尔以 FF 开头(原版解析法: 与 DD 同构)
                int end = -1;
                for (int e = i + 2; e + 1 < b.length; e++) {
                    if (b[e + 1] == (byte) 0xAA && (sum(b, i, e) & 0xFF) == (b[e] & 0xFF)) {
                        end = e;
                        break;
                    }
                }
                if (end < 0) return;
                onFrame(b, i, end - i + 2, FAMILY_V2);
                i = end + 2;
            } else {
                i++; // 杂字节, 找下一个帧头
            }
        }
    }

    private static int sum(byte[] b, int from, int toExclusive) {
        int s = 0;
        for (int k = from; k < toExclusive; k++) s += b[k] & 0xFF;
        return s;
    }

    private void onFrame(byte[] b, int start, int total, int fam) {
        if (!gotFirst) {
            gotFirst = true;
            h.removeCallbacks(replyTimeout);
        }
        try {
            if (fam == FAMILY_RS) {
                // DD 00 LEN TYPE VALUE... AA; 电量特例: type 0C, 3 字节左/右/盒
                int type = b[start + 3] & 0xFF;
                if (type == 0x0C && total >= 8) {
                    int l = b[start + 4] & 0x7F;
                    int r = b[start + 5] & 0x7F;
                    int c = b[start + 6] & 0xFF;
                    deliverBattery(l, r, c, 0);
                } else if (type == 0x09 && total >= 5) {
                    deliverMode(noiseModeFrom(b[start + 4] & 0xFF));
                }
                return;
            }
            // V2: 从第 2 字节起逐 TLV (L, TYPE, VALUE...)
            int i = start + 2;
            while (i < start + total - 2) {
                int l = b[i] & 0xFF;
                if (l == 0) break;
                int type = b[i + 1] & 0xFF;
                int vlen = l - 1;
                if (i + 2 + vlen + 2 > start + total) break;
                if (type == 0x0C && vlen == 3) {
                    int lv = b[i + 2] & 0xFF;
                    int rv = b[i + 3] & 0xFF;
                    int cv = b[i + 4] & 0xFF;
                    int flags = ((lv >> 7) & 1) | (((rv >> 7) & 1) << 1) | (((cv >> 7) & 1) << 2);
                    deliverBattery(lv & 0x7F, rv & 0x7F, cv & 0x7F, flags);
                } else if (type == 0x09 && vlen == 1) {
                    deliverMode(noiseModeFrom(b[i + 2] & 0xFF));
                } else if (type == 0xFE) {
                    // ACK: 两字节结果, 不等于成功, 忽略
                }
                i += 1 + l;
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse frame failed", t);
        }
    }

    private static int noiseModeFrom(int v) {
        if (v == 1) return MODE_ANC;
        if (v == 2) return MODE_OFF;
        if (v == 3) return MODE_TRANS;
        return -1; // 风噪(4)/未知: 三态 UI 不点亮任何一档, 别假装
    }

    private void deliverBattery(int l, int r, int c, int flags) {
        boolean changed = l != batt[0] || r != batt[1] || c != batt[2] || flags != chargeFlags;
        batt[0] = l;
        batt[1] = r;
        batt[2] = c;
        chargeFlags = flags;
        for (Listener li : snapshot()) {
            try {
                li.onBattery(l, r, c, flags);
            } catch (Throwable ignored) {
            }
        }
        if (changed) {
            // 电量是最常变的数据, UI 侧会据此刷新磁贴快照
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

    // ---- 生命周期 ----

    private void markConnected() {
        if (connected) return;
        connected = true;
        fireConnected(device != null && device.getName() != null ? device.getName() : "弱水时砂耳机");
        h.postDelayed(replyTimeout, 1500);
        h.postDelayed(heartbeat, 45000);
    }

    private void fail(final String msg) {
        Log.d(TAG, msg);
        final boolean wasConnected = connected;
        Runnable r = new Runnable() {
            @Override public void run() {
                if (!wasConnected) fireError(msg);
                else fireDisconnected(msg);
                disconnectInternal();
            }
        };
        // 契约 6.5: 连接失败先等耳机侧槽位释放(1.5s)再回调, 避免连-断循环
        if (wasConnected) h.post(r);
        else h.postDelayed(r, 1500);
    }

    private void failLater(final String msg) {
        h.post(new Runnable() {
            @Override public void run() {
                fail(msg);
            }
        });
    }

    private synchronized void disconnectInternal() {
        h.removeCallbacks(heartbeat);
        h.removeCallbacks(replyTimeout);
        try {
            if (sppSocket != null) sppSocket.close();
        } catch (Throwable ignored) {
        }
        sppSocket = null;
        sppOut = null;
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Throwable ignored) {
        }
        gatt = null;
        writeChar = null;
        connected = false;
        gotFirst = false;
    }

    private static void closeQuietly(BluetoothSocket s) {
        try {
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }

    @Override public void disconnect() {
        closing = true;
        disconnectInternal();
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
        if (connected) sendQuery();
    }

    @Override public void queryNoise() {
        if (connected) sendQuery();
    }

    @Override public void setNoiseMode(int mode) {
        if (!connected) return;
        sendNoise(mode);
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
        return "弱水时砂";
    }
}

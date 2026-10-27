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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 漫步者(EDIFIER)客户端(实验性).
 *
 * 协议依据: HyperEars/docs/edifier-bes-protocol.md、mEDIFIER、BudsLink 三份开源实现 + 社区抓包.
 * V2 帧: AA | EC | CMD | LEN_H | LEN_L | PAYLOAD | CRC8 ; payload 默认 XOR 0xA5; CRC8=sum(前序线上字节)&FF
 * V1 帧: AA | LEN | CMD | DATA | CRC16_H | CRC16_L ; LEN=len(DATA)+1; CRC16=(0x2019+sum)&FFFF
 * 电量: 头戴/老型号 D0(单值), TWS F2(6 字节 [peer][左][右][盒][充电][?]).
 * 降噪: CC 查询拿 [group][mode] → C1 设置; 每个 group 的 mode 含义不同, 不猜 group.
 * 传输: SPP(EDF00000-...) 优先, 失败走 BLE GATT(特征 48090001/48090002, 按 UUID 找, 不强绑服务).
 * 只发 D0/F2/CC/C1, 其余命令一律不发.
 */
public class EdifierClient implements TwsClient {

    private static final String TAG = "Edifier";

    private static final UUID SPP_UUID = UUID.fromString("edf00000-edfe-dfed-fedf-edfedfedfedf");
    private static final UUID BLE_NOTIFY = UUID.fromString("48090001-1a48-11e9-ab14-d663bd873d93");
    private static final UUID BLE_WRITE = UUID.fromString("48090002-1a48-11e9-ab14-d663bd873d93");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int CMD_BATT_D0 = 0xD0;
    private static final int CMD_BATT_F2 = 0xF2;
    private static final int CMD_ANC_QUERY = 0xCC;
    private static final int CMD_ANC_SET = 0xC1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final List<byte[]> rx = new ArrayList<byte[]>();
    private final Object frameLock = new Object();
    private int frameSeq;

    private Context app;
    private BluetoothDevice device;
    private volatile boolean closing;
    private volatile boolean connected;
    private boolean v2 = true;          // false=V1 帧
    private boolean xor = true;         // V2 payload 是否 XOR 0xA5
    private int batteryCmd = -1;        // 0=D0 单值, 1=F2 三段
    private int group = -1;

    private BluetoothSocket sppSocket;
    private volatile OutputStream sppOut;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writeChar;

    private int[] batt = {-1, -1, -1};
    private int chargeFlags;

    @Override public void init(Context ctx) {
        app = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override public void connect(BluetoothDevice target) {
        device = target;
        closing = false;
        connected = false;
        v2 = true;
        xor = true;
        batteryCmd = -1;
        group = -1;
        batt[0] = -1;
        batt[1] = -1;
        batt[2] = -1;
        chargeFlags = 0;
        closeAll();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!sppConnect()) {
                        if (!bleConnect()) {
                            fail("连不上漫步者控制通道(SPP/BLE 都没应答)");
                            return;
                        }
                    }
                    if (!probe()) {
                        fail("漫步者耳机没回应控制帧(可能型号/固件不支持)");
                        return;
                    }
                    connected = true;
                    h.post(new Runnable() {
                        @Override public void run() {
                            fireConnected(device != null && device.getName() != null
                                    ? device.getName() : "漫步者耳机");
                        }
                    });
                    // 拿到能力后再问一遍降噪(probe 里已问过, 这里刷一次给 UI)
                    sendAncQuery();
                } catch (Throwable t) {
                    Log.e(TAG, "connect failed", t);
                    fail("连接失败: " + t.getClass().getSimpleName());
                }
            }
        }, "edifier-conn");
        t.start();
    }

    // ---- 传输: SPP ----

    private boolean sppConnect() {
        try {
            sppSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            sppSocket.connect();
            sppOut = sppSocket.getOutputStream();
            startSppReader(sppSocket.getInputStream());
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "spp failed: " + t.getMessage());
            try {
                if (sppSocket != null) sppSocket.close();
            } catch (Throwable ignored) {
            }
            sppSocket = null;
            return false;
        }
    }

    private Thread sppReader;

    private void startSppReader(final InputStream in) {
        sppReader = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[512];
                try {
                    while (!closing) {
                        int n = in.read(buf);
                        if (n <= 0) break;
                        feed(buf, n);
                    }
                } catch (Throwable t) {
                    Log.d(TAG, "spp read end: " + t.getMessage());
                }
                if (!closing && connected) {
                    connected = false;
                    h.post(new Runnable() {
                        @Override public void run() {
                            fireDisconnected("耳机已断开");
                        }
                    });
                }
            }
        }, "edifier-read");
        sppReader.start();
    }

    // ---- 传输: BLE GATT ----

    private boolean bleConnect() {
        if (app == null) return false;
        final boolean[] ok = {false};
        final Object lock = new Object();
        try {
            gatt = device.connectGatt(app, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED && connected) {
                        connected = false;
                        h.post(new Runnable() {
                            @Override public void run() {
                                fireDisconnected("耳机已断开");
                            }
                        });
                    }
                }

                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    BluetoothGattCharacteristic nChar = findChar(g, BLE_NOTIFY);
                    writeChar = findChar(g, BLE_WRITE);
                    if (nChar == null || writeChar == null) {
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                        return;
                    }
                    g.setCharacteristicNotification(nChar, true);
                    BluetoothGattDescriptor d = nChar.getDescriptor(CCCD);
                    if (d != null) {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(d);
                    }
                    synchronized (lock) {
                        ok[0] = true;
                        lock.notifyAll();
                    }
                }

                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    byte[] v = c.getValue();
                    if (v != null) feed(v, v.length);
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            Log.d(TAG, "ble failed: " + t.getMessage());
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
        // 给 notify 注册留一点时间, 免得第一批查询帧被吞
        sleep(250);
        return true;
    }

    private static BluetoothGattCharacteristic findChar(BluetoothGatt g, UUID uuid) {
        for (android.bluetooth.BluetoothGattService s : g.getServices()) {
            if (s == null) continue;
            BluetoothGattCharacteristic c = s.getCharacteristic(uuid);
            if (c != null) return c;
        }
        return null;
    }

    // ---- 探测: 定帧版本/加密/电量命令 ----

    private boolean probe() {
        // 尝试序列: V2+xor D0 → V2+xor F2 → V2 明文 D0/F2 → V1 D0/F2
        int[][] tries = {
                {1, 0}, {1, 1},   // v2=1(是), cmd 0=D0 1=F2
                {1, 0}, {1, 1},   // 明文重试, 由下面 xor 标志控制
                {0, 0}, {0, 1},   // V1
        };
        boolean[] xorPlan = {true, true, false, false, true, true};
        for (int i = 0; i < tries.length; i++) {
            if (closing) return false;
            v2 = tries[i][0] == 1;
            xor = xorPlan[i];
            int cmd = tries[i][1] == 0 ? CMD_BATT_D0 : CMD_BATT_F2;
            sendFrame(cmd, new byte[0]);
            if (waitFrame(1100)) {
                if (batteryCmd < 0) batteryCmd = tries[i][1];
                // 再问一次降噪拿 group(不阻塞成功判定)
                sendAncQuery();
                waitFrame(900);
                return true;
            }
            // 如果耳机只读了一次唤醒: 同一配置重发一次再等
            if (i == 0 || i == 1) {
                sendFrame(cmd, new byte[0]);
                if (waitFrame(900)) {
                    if (batteryCmd < 0) batteryCmd = tries[i][1];
                    sendAncQuery();
                    waitFrame(900);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean waitFrame(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        synchronized (frameLock) {
            int start = frameSeq;
            while (frameSeq == start) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    frameLock.wait(left);
                } catch (InterruptedException ignored) {
                    return frameSeq != start;
                }
            }
            return true;
        }
    }

    // ---- 发送 ----

    private void sendFrame(int cmd, byte[] data) {
        byte[] frame = v2 ? buildV2(cmd, data) : buildV1(cmd, data);
        sendRaw(frame);
    }

    private byte[] buildV2(int cmd, byte[] data) {
        int len = data.length;
        byte[] f = new byte[6 + len + 1];
        f[0] = (byte) 0xAA;
        f[1] = (byte) 0xEC;
        f[2] = (byte) cmd;
        f[3] = (byte) ((len >> 8) & 0xFF);
        f[4] = (byte) (len & 0xFF);
        for (int i = 0; i < len; i++) {
            f[5 + i] = (byte) (data[i] ^ (xor ? 0xA5 : 0));
        }
        int crc = 0;
        for (int i = 0; i < 5 + len; i++) crc = (crc + (f[i] & 0xFF)) & 0xFF;
        f[5 + len] = (byte) crc;
        return f;
    }

    private byte[] buildV1(int cmd, byte[] data) {
        int len = data.length + 1;
        byte[] f = new byte[3 + len + 2];
        f[0] = (byte) 0xAA;
        f[1] = (byte) len;
        f[2] = (byte) cmd;
        for (int i = 0; i < data.length; i++) f[3 + i] = data[i];
        int sum = 0x2019;
        for (int i = 0; i < 3 + data.length; i++) sum += f[i] & 0xFF;
        f[3 + data.length] = (byte) ((sum >> 8) & 0xFF);
        f[3 + data.length + 1] = (byte) (sum & 0xFF);
        return f;
    }

    private synchronized void sendRaw(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        try {
            if (sppOut != null) {
                sppOut.write(frame);
                sppOut.flush();
            } else if (gatt != null && writeChar != null) {
                // BLE 按 20 字节分片, 片间留 30ms
                for (int off = 0; off < frame.length; off += 20) {
                    int n = Math.min(20, frame.length - off);
                    byte[] chunk = Arrays.copyOfRange(frame, off, off + n);
                    writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    writeChar.setValue(chunk);
                    gatt.writeCharacteristic(writeChar);
                    sleep(30);
                }
            }
        } catch (Throwable t) {
            fail("发送失败: " + t.getMessage());
        }
    }

    // ---- 接收 ----

    private void feed(byte[] data, int len) {
        synchronized (rx) {
            rx.add(Arrays.copyOfRange(data, 0, len));
        }
        parse();
    }

    private void parse() {
        byte[] buf;
        synchronized (rx) {
            if (rx.isEmpty()) return;
            int total = 0;
            for (byte[] b : rx) total += b.length;
            // 契约: 别让无帧头的垃圾无限堆积(慢速内存泄漏)
            if (total > 1024) {
                rx.clear();
                Log.w(TAG, "rx overflow, pending buffer dropped");
                return;
            }
            buf = new byte[total];
            int p = 0;
            for (byte[] b : rx) {
                System.arraycopy(b, 0, buf, p, b.length);
                p += b.length;
            }
            rx.clear();
        }
        int i = 0;
        while (i < buf.length) {
            int hdr = buf[i] & 0xFF;
            if (hdr != 0xAA && hdr != 0xBB && hdr != 0xCC) {
                i++;
                continue;
            }
            if (i + 1 < buf.length && (buf[i + 1] & 0xFF) == 0xEC) {
                // V2
                if (i + 5 > buf.length) break;
                int len = ((buf[i + 3] & 0xFF) << 8) | (buf[i + 4] & 0xFF);
                // 二次校验: 长度不合理就不是帧头(0xCC 既是老固件响应头又是降噪命令号, 易出假帧)
                if (len > 512) {
                    i++;
                    continue;
                }
                int total = 6 + len;
                if (i + total > buf.length) break;
                int crc = 0;
                for (int k = i; k < i + total - 1; k++) crc = (crc + (buf[k] & 0xFF)) & 0xFF;
                if ((buf[i + total - 1] & 0xFF) != crc) {
                    i++;
                    continue;
                }
                byte[] payload = new byte[len];
                for (int k = 0; k < len; k++) {
                    payload[k] = (byte) ((buf[i + 5 + k] & 0xFF) ^ (xor ? 0xA5 : 0));
                }
                onFrame(buf[i + 2] & 0xFF, payload);
                i += total;
            } else {
                // V1
                if (i + 2 > buf.length) break;
                int len = buf[i + 1] & 0xFF;
                // 二次校验: V1 帧的 LEN = len(DATA)+1, 本项目只收发很小的控制帧
                if (len < 1 || len > 64) {
                    i++;
                    continue;
                }
                int total = 4 + len;
                if (i + total > buf.length) break;
                int sum = 0x2019;
                for (int k = i; k < i + 4 + len - 2; k++) sum += buf[k] & 0xFF;
                int crc = ((buf[i + total - 2] & 0xFF) << 8) | (buf[i + total - 1] & 0xFF);
                if ((sum & 0xFFFF) != crc) {
                    i++;
                    continue;
                }
                byte[] payload = new byte[Math.max(0, len - 1)];
                for (int k = 0; k < payload.length; k++) payload[k] = buf[i + 3 + k];
                onFrame(buf[i + 2] & 0xFF, payload);
                i += total;
            }
        }
        synchronized (rx) {
            if (i < buf.length) rx.add(Arrays.copyOfRange(buf, i, buf.length));
        }
    }

    private void onFrame(int cmd, byte[] payload) {
        synchronized (frameLock) {
            frameSeq++;
            frameLock.notifyAll();
        }
        try {
            if (cmd == CMD_BATT_D0 && payload.length >= 1) {
                int v = payload[0] & 0x7F;
                if (v >= 1 && v <= 100) {
                    batteryCmd = 0;
                    deliverBattery(v, 0xFF, 0xFF, 0);
                }
            } else if (cmd == CMD_BATT_F2 && payload.length >= 5) {
                int l = payload[1] & 0xFF;
                int r = payload[2] & 0xFF;
                int c = payload[3] & 0xFF;
                batteryCmd = 1;
                deliverBattery(l, r, c, 0);
            } else if (cmd == CMD_ANC_QUERY && payload.length >= 2) {
                if (v2) {
                    group = payload[0] & 0xFF;
                    deliverMode(mapMode(group, payload[1] & 0xFF));
                } else {
                    deliverMode(mapV1Mode(payload[0] & 0xFF));
                }
            } else {
                // 契约 6.6.3: 未知帧全量打 hex, 这是协议分析的 ground truth
                Log.d(TAG, "rx unknown cmd=0x" + Integer.toHexString(cmd) + " payload=" + hex(payload));
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse frame failed", t);
        }
    }

    /** V2 每个 group 的 mode 含义不同, 必须按 group 查表(见协议文档) */
    private static int mapMode(int group, int mode) {
        switch (group) {
            case 0x10: // W860NB PRO: 1/2 降噪, 3 防风, 4 环境声, 5 关
                if (mode == 1 || mode == 2) return MODE_ANC;
                if (mode == 4) return MODE_TRANS;
                if (mode == 5) return MODE_OFF;
                return -1;
            case 0x1B: // Evo Pro / FitBuds Turbo: 1/2/3 降噪, 4 防风, 5 环境声, 6 关
                if (mode >= 1 && mode <= 3) return MODE_ANC;
                if (mode == 5) return MODE_TRANS;
                if (mode == 6) return MODE_OFF;
                return -1;
            case 0x17: // X5 Pro: 1 降噪, 2 环境声, 3 关
                if (mode == 1) return MODE_ANC;
                if (mode == 2) return MODE_TRANS;
                if (mode == 3) return MODE_OFF;
                return -1;
            default:
                return -1;
        }
    }

    /** V1(W820NB 等): 1 关 / 2 降噪 / 3 环境声 */
    private static int mapV1Mode(int mode) {
        if (mode == 1) return MODE_OFF;
        if (mode == 2) return MODE_ANC;
        if (mode == 3) return MODE_TRANS;
        return -1;
    }

    private int[] deviceModeFor(int mode) {
        // 返回 {group, mode} 或 {mode} (V1), 未知 group 返回 null
        if (!v2) {
            if (mode == MODE_ANC) return new int[]{2};
            if (mode == MODE_OFF) return new int[]{1};
            if (mode == MODE_TRANS) return new int[]{3};
            return null;
        }
        switch (group) {
            case 0x10:
                if (mode == MODE_ANC) return new int[]{0x10, 1};
                if (mode == MODE_OFF) return new int[]{0x10, 5};
                if (mode == MODE_TRANS) return new int[]{0x10, 4};
                return null;
            case 0x1B:
                if (mode == MODE_ANC) return new int[]{0x1B, 1};
                if (mode == MODE_OFF) return new int[]{0x1B, 6};
                if (mode == MODE_TRANS) return new int[]{0x1B, 5};
                return null;
            case 0x17:
                if (mode == MODE_ANC) return new int[]{0x17, 1};
                if (mode == MODE_OFF) return new int[]{0x17, 3};
                if (mode == MODE_TRANS) return new int[]{0x17, 2};
                return null;
            default:
                return null;
        }
    }

    private void sendAncQuery() {
        sendFrame(CMD_ANC_QUERY, new byte[0]);
    }

    private void deliverBattery(int l, int r, int c, int flags) {
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
    }

    private void deliverMode(int mode) {
        if (mode < 0) return;
        for (Listener li : snapshot()) {
            try {
                li.onNoiseMode(mode);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- 生命周期/工具 ----

    private void fail(final String msg) {
        Log.d(TAG, msg);
        final boolean wasConnected = connected;
        Runnable r = new Runnable() {
            @Override public void run() {
                if (!wasConnected) fireError(msg);
                else fireDisconnected(msg);
                closeAll();
            }
        };
        // 契约 6.5: 连接失败先等耳机侧槽位释放(1.5s)再回调, 避免连-断循环
        if (wasConnected) h.post(r);
        else h.postDelayed(r, 1500);
    }

    private void closeAll() {
        connected = false;
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
    }

    private static String hex(byte[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            int v = x & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v)).append(' ');
        }
        return sb.toString().trim();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    @Override public void disconnect() {
        closing = true;
        closeAll();
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
        if (!connected) return;
        if (batteryCmd == 1) sendFrame(CMD_BATT_F2, new byte[0]);
        else sendFrame(CMD_BATT_D0, new byte[0]);
    }

    @Override public void queryNoise() {
        if (connected) sendAncQuery();
    }

    @Override public void setNoiseMode(int mode) {
        if (!connected) return;
        int[] dm = deviceModeFor(mode);
        if (dm == null) return;
        if (v2 && dm.length == 2) {
            sendFrame(CMD_ANC_SET, new byte[]{(byte) dm[0], (byte) dm[1]});
        } else {
            sendFrame(CMD_ANC_SET, new byte[]{(byte) dm[0]});
        }
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (connected) sendAncQuery();
            }
        }, 350);
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
        // 头戴型号只有 D0 单值电量; TWS 是 F2 三段
        if (batteryCmd == 0) return CAP_BATTERY_SINGLE | CAP_NOISE;
        return CAP_BATTERY | CAP_NOISE;
    }

    @Override public int verifLevel() {
        return VERIF_UNTESTED;
    }

    @Override public String brandName() {
        return "漫步者";
    }
}

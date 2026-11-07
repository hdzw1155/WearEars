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
 * 电量: D0 单值；F2 只发布已证实的左右耳字段，盒与充电标志保持未知。
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
    private final ProtocolWriter writer = new ProtocolWriter();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final EdifierProtocol.Decoder decoder = new EdifierProtocol.Decoder();
    private final Object frameLock = new Object();
    private int frameSeq;

    private Context app;
    private BluetoothDevice device;
    private volatile boolean closing;
    private volatile boolean connected;
    private volatile boolean v2 = true;          // false=V1 帧
    private volatile boolean xor = true;         // V2 payload 是否 XOR 0xA5
    private volatile int batteryCmd = -1;        // 0=D0 单值, 1=F2 三段
    private volatile int group = -1;
    private volatile boolean noiseConfirmed;

    private volatile BluetoothSocket sppSocket;
    private volatile OutputStream sppOut;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writeChar;

    private int[] batt = {-1, -1, -1};
    private int chargeFlags;

    @Override public void init(Context ctx) {
        app = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override public void connect(BluetoothDevice target) {
        disconnect();
        writer.start();
        device = target;
        closing = false;
        connected = false;
        v2 = true;
        String name = target == null || target.getName() == null ? ""
                : target.getName().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_-]", "");
        xor = !("edifierfitbudsturbo".equals(name) || "fitbudsturbo".equals(name));
        noiseConfirmed = false;
        decoder.reset();
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
                        if (closing) return;
                        if (!bleConnect()) {
                            fail("连不上漫步者控制通道(SPP/BLE 都没应答)");
                            return;
                        }
                    }
                    if (!probe()) {
                        fail("漫步者耳机没回应控制帧(可能型号/固件不支持)");
                        return;
                    }
                    if (closing) { closeAll(); return; }
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
        if (closing) return false;
        try {
            sppSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            if (closing) { closeAll(); return false; }
            sppSocket.connect();
            if (closing) { closeAll(); return false; }
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
        final BluetoothSocket source = sppSocket;
        sppReader = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[512];
                try {
                    while (!closing && sppSocket == source) {
                        int n = in.read(buf);
                        if (closing || sppSocket != source) break;
                        if (n <= 0) break;
                        feed(buf, n);
                    }
                } catch (Throwable t) {
                    Log.d(TAG, "spp read end: " + t.getMessage());
                }
                if (!closing && sppSocket == source && connected) {
                    connected = false;
                    h.post(new Runnable() {
                        @Override public void run() {
                            if (!closing && sppSocket == source) fireDisconnected("耳机已断开");
                        }
                    });
                }
            }
        }, "edifier-read");
        sppReader.start();
    }

    // ---- 传输: BLE GATT ----

    private boolean bleConnect() {
        if (closing) return false;
        if (app == null) return false;
        final boolean[] ok = {false}, completed = {false};
        final Object lock = new Object();
        try {
            gatt = device.connectGatt(app, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (closing) { g.close(); return; }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !connected) {
                        finish(false);
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
                    if (closing) { g.close(); return; }
                    BluetoothGattCharacteristic nChar = findChar(g, BLE_NOTIFY);
                    writeChar = findChar(g, BLE_WRITE);
                    if (status != BluetoothGatt.GATT_SUCCESS || nChar == null || writeChar == null
                            || !g.setCharacteristicNotification(nChar, true)) {
                        finish(false); return;
                    }
                    BluetoothGattDescriptor d = nChar.getDescriptor(CCCD);
                    if (d == null) { finish(false); return; }
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(d)) finish(false);
                }

                private void finish(boolean success) {
                    synchronized (lock) { ok[0] = success; completed[0] = true; lock.notifyAll(); }
                }
                @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
                    if (!closing && CCCD.equals(descriptor.getUuid())) finish(status == BluetoothGatt.GATT_SUCCESS);
                }

                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    if (closing) return;
                    byte[] v = c.getValue();
                    if (v != null) feed(v, v.length);
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            Log.d(TAG, "ble failed: " + t.getMessage());
            return false;
        }
        if (closing) { closeAll(); return false; }
        if (gatt == null) return false;
        synchronized (lock) {
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000L;
            while (!completed[0] && !closing) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try { lock.wait(Math.min(remaining, 250L)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (closing) { closeAll(); return false; }
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
        // 三种只读方言；明确的明文型号优先明文，不能凭任意回包宣布探测成功。
        boolean preferredXor = xor;
        for (int dialect = 0; dialect < 3 && !closing; dialect++) {
            v2 = dialect != 2;
            xor = dialect == 0 ? preferredXor : !preferredXor;
            int before;
            synchronized (frameLock) { before = frameSeq; }
            sendFrame(CMD_BATT_F2, new byte[0]);
            sendFrame(CMD_BATT_D0, new byte[0]);
            sendAncQuery();
            if (waitFrame(before, 1500L)) return true;
        }
        return false;
    }

    private boolean waitFrame(int before, long timeout) {
        long end = android.os.SystemClock.elapsedRealtime() + timeout;
        synchronized (frameLock) {
            while (!closing && frameSeq == before) {
                long remaining = end - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) return false;
                try { frameLock.wait(remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            return !closing && frameSeq != before;
        }
    }

    // ---- 发送 ----

    private void sendFrame(int cmd, byte[] data) {
        byte[] frame = EdifierProtocol.encode(cmd, data, v2, xor);
        sendRaw(frame);
    }



    private void sendRaw(final byte[] frame) {
        if (closing || frame == null || frame.length == 0) return;
        final OutputStream stream = sppOut;
        final BluetoothGatt ble = gatt;
        final BluetoothGattCharacteristic characteristic = writeChar;
        if (!writer.post(new Runnable() {
            @Override public void run() {
                if (!closing && stream == sppOut && ble == gatt)
                    writeRaw(frame, stream, ble, characteristic);
            }
        }) && !closing) fireError("控制指令过多，请稍后重试");
    }

    private void writeRaw(byte[] frame, OutputStream stream, BluetoothGatt ble,
            BluetoothGattCharacteristic characteristic) {
        if (closing || frame == null || frame.length == 0) return;
        try {
            if (stream != null) {
                stream.write(frame);
                stream.flush();
            } else if (ble != null && characteristic != null) {
                // BLE 按 20 字节分片, 片间留 30ms
                for (int off = 0; off < frame.length && !closing && ble == gatt
                        && !Thread.currentThread().isInterrupted(); off += 20) {
                    int n = Math.min(20, frame.length - off);
                    byte[] chunk = Arrays.copyOfRange(frame, off, off + n);
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    characteristic.setValue(chunk);
                    if (!ble.writeCharacteristic(characteristic)) throw new IllegalStateException("BLE busy");
                    sleep(30);
                }
            }
        } catch (Throwable t) {
            if (!closing && stream == sppOut && ble == gatt) fail("发送失败: " + t.getMessage());
        }
    }

    // ---- 接收 ----

    private void feed(byte[] data, int len) {
        if (closing) return;
        for (EdifierProtocol.Frame frame : decoder.feed(data, len)) {
            if (frame.v2 != v2) continue;
            byte[] payload = frame.payload(xor);
            boolean recognized = false;
            int[] battery = EdifierProtocol.battery(frame.command, payload);
            if (battery != null) {
                // F2 优先；稍后到达的 D0 聚合电量不能把 TWS 切成单电池。
                if (frame.command == CMD_BATT_F2 || batteryCmd != 1) {
                    batteryCmd = frame.command == CMD_BATT_F2 ? 1 : 0;
                    deliverBattery(battery[0], battery[1], battery[2], battery[3]);
                }
                recognized = true;
            } else if (frame.command == CMD_ANC_QUERY && (v2 ? payload.length >= 2 && payload.length <= 3 : payload.length >= 1 && payload.length <= 2)) {
                int returnedGroup = v2 ? payload[0] & 255 : 0;
                int value = payload[v2 ? 1 : 0] & 255;
                int mode = EdifierProtocol.mode(returnedGroup, value);
                boolean wind = returnedGroup == 0x10 && value == 3 || returnedGroup == 0x1B && value == 4;
                if (mode >= 0 || wind) {
                    group = returnedGroup; noiseConfirmed = true; recognized = true;
                    deliverMode(mode);
                }
            }
            if (recognized) synchronized (frameLock) {
                frameSeq++; frameLock.notifyAll();
            }
            else Log.d(TAG, "rx unsupported cmd=" + Integer.toHexString(frame.command) + " payload=" + hex(payload));
        }
    }

    private void sendAncQuery() {
        sendFrame(CMD_ANC_QUERY, new byte[0]);
    }

    private void deliverBattery(int l, int r, int c, int flags) {
        if (closing) return;
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
        if (closing) return;
        for (Listener li : snapshot()) {
            try {
                li.onNoiseMode(mode);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- 生命周期/工具 ----

    private void fail(final String msg) {
        if (closing) return;
        Log.d(TAG, msg);
        final boolean wasConnected = connected;
        Runnable r = new Runnable() {
            @Override public void run() {
                if (closing) return;
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
        writer.stop();
        h.removeCallbacksAndMessages(null);
        synchronized (frameLock) { frameLock.notifyAll(); }
        closeAll();
        decoder.reset();
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
        if (closing) return;
        for (Listener l : snapshot()) {
            try {
                l.onConnected(name);
            } catch (Throwable ignored) {
            }
        }
    }

    private void fireDisconnected(final String reason) {
        if (closing) return;
        for (Listener l : snapshot()) {
            try {
                l.onDisconnected(reason);
            } catch (Throwable ignored) {
            }
        }
    }

    private void fireError(final String msg) {
        if (closing) return;
        for (Listener l : snapshot()) {
            try {
                l.onError(msg);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override public void queryBattery() {
        if (!connected) return;
        if (batteryCmd != 0) sendFrame(CMD_BATT_F2, new byte[0]);
        if (batteryCmd != 1) sendFrame(CMD_BATT_D0, new byte[0]);
    }

    @Override public void queryNoise() {
        if (connected) sendAncQuery();
    }

    @Override public void setNoiseMode(int mode) {
        if (!connected) return;
        byte[] target = noiseConfirmed ? EdifierProtocol.modeCommand(v2, group, mode) : null;
        if (target == null) { fireError("该耳机尚未确认降噪控制协议"); return; }
        sendFrame(CMD_ANC_SET, target);
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
        return (batteryCmd == 0 ? CAP_BATTERY_SINGLE : CAP_BATTERY) | (noiseConfirmed ? CAP_NOISE : 0);
    }

    @Override public int verifLevel() {
        return VERIF_UNTESTED;
    }

    @Override public String brandName() {
        return "漫步者";
    }
}

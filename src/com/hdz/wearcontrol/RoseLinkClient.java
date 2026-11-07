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
import android.os.HandlerThread;
import android.os.Looper;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** RoseLink 已知 RS/V2 型号：先订阅、再查询，以有效状态包确认连接。 */
public final class RoseLinkClient implements TwsClient {
    private static final UUID SPP = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3931");
    private static final UUID SERVICE = UUID.fromString("000000fe-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("000000f1-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("000000f2-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private Context app;
    private volatile BluetoothSocket socket;
    private volatile OutputStream output;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic write;
    private volatile boolean closing = true, connected, noiseConfirmed;
    private HandlerThread writerThread;
    private Handler writer;
    private RoseProtocol.Decoder decoder;
    private int family, sequence;
    private String name;
    private long lastQuery;
    private final Runnable confirm = new Runnable() {
        @Override public void run() { queryBattery(); }
    };
    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            if (!connected) fail("耳机未返回 RoseLink 状态");
        }
    };

    @Override public void init(Context context) { app = context == null ? null : context.getApplicationContext(); }

    @Override public void connect(final BluetoothDevice device) {
        disconnect();
        name = device.getName() == null ? "" : device.getName();
        family = RoseProtocol.familyForName(name);
        closing = false;
        noiseConfirmed = false;
        sequence = 0;
        lastQuery = 0;
        decoder = new RoseProtocol.Decoder(family);
        if (family < 0) { fail("该型号的 RoseLink 协议尚未确认"); return; }
        writerThread = new HandlerThread("rose-write");
        writerThread.start();
        writer = new Handler(writerThread.getLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                if (openSpp(device)) return;
                if (!closing) openBle(device);
            }
        }, "rose-read").start();
    }

    /** 只有建链失败才试 BLE；已连接通道的断开不触发另一条后台连接。 */
    private boolean openSpp(BluetoothDevice device) {
        InputStream input;
        try {
            BluetoothSocket candidate = device.createRfcommSocketToServiceRecord(SPP);
            socket = candidate;
            if (closing) { candidate.close(); return true; }
            candidate.connect();
            if (closing) { candidate.close(); return true; }
            input = candidate.getInputStream();
            output = candidate.getOutputStream();
        } catch (Exception e) {
            closeSocket();
            return false;
        }
        beginQuery();
        try {
            byte[] bytes = new byte[512];
            int count;
            while (!closing && (count = input.read(bytes)) != -1) feed(bytes, count);
        } catch (Exception ignored) { }
        if (!closing) fail("耳机已断开");
        return true;
    }

    private void openBle(BluetoothDevice device) {
        try {
            gatt = device.connectGatt(app, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                    if (closing) { g.close(); return; }
                    if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                        fail("RoseLink 蓝牙连接已断开");
                    } else if (state == BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) {
                        fail("无法读取 RoseLink 服务");
                    }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    if (closing) { g.close(); return; }
                    if (status != BluetoothGatt.GATT_SUCCESS || g.getService(SERVICE) == null) {
                        fail("耳机未开放 RoseLink BLE 服务"); return;
                    }
                    write = g.getService(SERVICE).getCharacteristic(WRITE);
                    BluetoothGattCharacteristic notify = g.getService(SERVICE).getCharacteristic(NOTIFY);
                    BluetoothGattDescriptor descriptor = notify == null ? null : notify.getDescriptor(CCCD);
                    if (write == null || descriptor == null || !g.setCharacteristicNotification(notify, true)) {
                        fail("RoseLink 通知通道不可用"); return;
                    }
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(descriptor)) fail("无法订阅 RoseLink 通知");
                }
                @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                    if (closing || !CCCD.equals(d.getUuid())) return;
                    if (status == BluetoothGatt.GATT_SUCCESS) beginQuery();
                    else fail("订阅 RoseLink 通知失败");
                }
                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    if (!closing && NOTIFY.equals(c.getUuid())) {
                        byte[] bytes = c.getValue();
                        if (bytes != null) feed(bytes, bytes.length);
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE);
            if (closing && gatt != null) { gatt.close(); gatt = null; }
            else if (gatt == null) fail("无法打开 RoseLink BLE 通道");
        } catch (Exception e) { fail("无法打开 RoseLink BLE 通道"); }
    }

    private void beginQuery() {
        if (closing) return;
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, 4000);
        queryBattery();
    }

    private void feed(byte[] bytes, int count) {
        for (RoseProtocol.Item item : decoder.feed(bytes, count)) {
            if (closing) return;
            if (item.type == 0x0C) {
                int[] value = RoseProtocol.battery(family, item.value);
                if (value == null) continue;
                markConnected();
                for (Listener l : listeners) l.onBattery(value[0], value[1], value[2], value[3]);
            } else if (item.type == 9 && item.value.length == 1) {
                int mode = RoseProtocol.mode(item.value[0] & 255);
                if (mode < 0) continue;
                noiseConfirmed = RoseProtocol.supportsNoise(name);
                markConnected();
                if (noiseConfirmed) for (Listener l : listeners) l.onNoiseMode(mode);
            }
        }
    }

    private synchronized void markConnected() {
        if (closing || connected) return;
        connected = true;
        main.removeCallbacks(timeout);
        for (Listener l : listeners) l.onConnected(name);
    }

    private void send(final byte[] bytes) {
        Handler queue = writer;
        if (closing || queue == null) return;
        queue.post(new Runnable() {
            @Override public void run() {
                if (closing) return;
                try {
                    OutputStream stream = output;
                    if (stream != null) {
                        stream.write(bytes);
                        stream.flush();
                    } else {
                        BluetoothGatt g = gatt;
                        BluetoothGattCharacteristic c = write;
                        if (g == null || c == null) return;
                        // 默认 ATT 负载只有 20 字节，完整查询不能直接塞入一个特征值。
                        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                        for (int offset = 0; offset < bytes.length && !closing; offset += 20) {
                            c.setValue(java.util.Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 20)));
                            if (!g.writeCharacteristic(c)) throw new java.io.IOException("BLE write");
                            if (offset + 20 < bytes.length) Thread.sleep(40);
                        }
                    }
                } catch (Exception e) { fail("RoseLink 发送失败"); }
            }
        });
    }

    private void fail(final String message) {
        if (closing) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (closing) return;
                boolean wasConnected = connected;
                disconnect();
                for (Listener l : listeners) {
                    if (wasConnected) l.onDisconnected(message); else l.onError(message);
                }
            }
        });
    }

    private void closeSocket() {
        BluetoothSocket current = socket;
        socket = null;
        output = null;
        try { if (current != null) current.close(); } catch (Exception ignored) { }
    }

    @Override public void disconnect() {
        closing = true;
        connected = false;
        main.removeCallbacksAndMessages(null);
        if (writer != null) writer.removeCallbacksAndMessages(null);
        if (writerThread != null) writerThread.quitSafely();
        writer = null;
        writerThread = null;
        closeSocket();
        BluetoothGatt current = gatt;
        gatt = null;
        write = null;
        if (current != null) {
            try { current.disconnect(); current.close(); } catch (Exception ignored) { }
        }
    }

    @Override public synchronized void queryBattery() {
        if (closing) return;
        long now = android.os.SystemClock.elapsedRealtime();
        // 电量和模式共用 getInfo，合并控制器同一轮的两次读取。
        if (lastQuery != 0 && now - lastQuery < 300) return;
        lastQuery = now;
        send(RoseProtocol.query(family, sequence++));
    }
    @Override public void queryNoise() { queryBattery(); }
    @Override public synchronized void setNoiseMode(int mode) {
        if (!connected || !noiseConfirmed) return;
        int value = mode == MODE_ANC ? 1 : mode == MODE_OFF ? 2 : mode == MODE_TRANS ? 3 : -1;
        if (value < 0) return;
        send(RoseProtocol.encode(family, sequence++, new byte[]{9, (byte) value}));
        main.removeCallbacks(confirm);
        main.postDelayed(confirm, 700);
    }
    @Override public boolean isConnected() { return connected && !closing; }
    @Override public void addListener(Listener l) { if (l != null) listeners.addIfAbsent(l); }
    @Override public void removeListener(Listener l) { listeners.remove(l); }
    @Override public int caps() { return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0); }
    @Override public int verifLevel() { return VERIF_UNTESTED; }
    @Override public String brandName() { return "弱水时砂"; }
    @Override public void querySpatial() { }
    @Override public void setSpatial(boolean on) { }
    @Override public void queryDeepX() { }
    @Override public void setDeepX(int mode) { }
}

package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** 私有 RFCOMM 驱动共用传输。会话独立、断开可取消连接，读写不占用界面线程。 */
abstract class RfcommClient implements TwsClient {
    protected final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private volatile Session current;

    private static final class Session {
        final BluetoothDevice device;
        final HandlerThread thread = new HandlerThread("headset-protocol");
        final Handler queue;
        volatile BluetoothSocket socket;
        volatile OutputStream output;
        volatile boolean ready;
        Session(BluetoothDevice device) {
            this.device = device;
            thread.start();
            queue = new Handler(thread.getLooper());
        }
    }

    protected abstract UUID[] services(BluetoothDevice device);
    protected abstract void opened();
    protected abstract void received(byte[] data);
    @Override public void init(Context context) { }

    @Override public final synchronized void connect(final BluetoothDevice device) {
        disconnect();
        final Session session = new Session(device);
        current = session;
        session.queue.post(new Runnable() {
            @Override public void run() {
                for (UUID service : services(device)) {
                    if (current != session) return;
                    BluetoothSocket socket = null;
                    try {
                        socket = device.createRfcommSocketToServiceRecord(service);
                        session.socket = socket;
                        if (current != session) { close(socket); return; }
                        socket.connect();
                        if (current != session) { close(socket); return; }
                        session.output = socket.getOutputStream();
                        final InputStream input = socket.getInputStream();
                        opened();
                        new Thread(new Runnable() {
                            @Override public void run() { read(session, input); }
                        }, "headset-rfcomm").start();
                        return;
                    } catch (Exception e) {
                        close(socket);
                        session.socket = null;
                        session.output = null;
                    }
                }
                if (current == session) fail("无法打开耳机控制通道");
            }
        });
    }

    private void read(final Session session, InputStream input) {
        byte[] buffer = new byte[512];
        try {
            int length;
            while (current == session && (length = input.read(buffer)) != -1) {
                final byte[] bytes = Arrays.copyOf(buffer, length);
                session.queue.post(new Runnable() {
                    @Override public void run() { if (current == session) received(bytes); }
                });
            }
        } catch (Exception ignored) { }
        session.queue.post(new Runnable() {
            @Override public void run() { if (current == session) fail("耳机已断开"); }
        });
    }

    /** 驱动状态只在本会话队列中变更；已退出会话的任务不执行。 */
    protected final void schedule(final Runnable task, long delay) {
        final Session session = current;
        if (session == null) return;
        session.queue.postDelayed(new Runnable() {
            @Override public void run() { if (current == session) task.run(); }
        }, delay);
    }

    /** 只由会话队列调用，保证帧与帧不交叉写。 */
    protected final void write(byte[] bytes) {
        Session session = current;
        if (session == null || session.output == null
                || android.os.Looper.myLooper() != session.queue.getLooper()) return;
        try {
            session.output.write(bytes);
            session.output.flush();
        } catch (Exception e) { fail("耳机控制指令发送失败"); }
    }

    protected final void ready() {
        Session session = current;
        if (session == null || session.ready || android.os.Looper.myLooper() != session.queue.getLooper()) return;
        session.ready = true;
        String name = session.device.getName();
        for (Listener l : listeners) l.onConnected(name == null ? brandName() : name);
    }

    protected final void fail(String reason) {
        Session session = current;
        if (session == null || android.os.Looper.myLooper() != session.queue.getLooper()) return;
        boolean connected = session.ready;
        disconnect();
        for (Listener l : listeners) {
            if (connected) l.onDisconnected(reason); else l.onError(reason);
        }
    }

    @Override public final synchronized void disconnect() {
        Session session = current;
        current = null;
        if (session == null) return;
        session.queue.removeCallbacksAndMessages(null);
        close(session.socket);
        session.output = null;
        session.thread.quitSafely();
    }

    private static void close(BluetoothSocket socket) {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }
    @Override public boolean isConnected() { Session s = current; return s != null && s.ready; }
    protected final String deviceName() {
        Session s = current;
        if (s == null || s.device == null) return "";
        String name = s.device.getName();
        return name == null ? "" : name;
    }
    @Override public void addListener(Listener l) { if (l != null) listeners.addIfAbsent(l); }
    @Override public void removeListener(Listener l) { listeners.remove(l); }
    @Override public int verifLevel() { return VERIF_UNTESTED; }
    @Override public void querySpatial() { }
    @Override public void setSpatial(boolean on) { }
    @Override public void queryDeepX() { }
    @Override public void setDeepX(int mode) { }
}

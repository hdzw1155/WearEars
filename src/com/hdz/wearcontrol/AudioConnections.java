package com.hdz.wearcontrol;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次性查询系统音频连接，供自动选设备、标准驱动和磁贴共用，不扫描或连接耳机。 */
final class AudioConnections {
    interface Callback { void onResult(List<BluetoothDevice> devices, boolean complete); }
    static int profile(String action) {
        if ("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)) return BluetoothProfile.A2DP;
        if ("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED".equals(action)) return BluetoothProfile.HEADSET;
        if ("android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED".equals(action)) return BluetoothProfile.LE_AUDIO;
        return -1;
    }

    static void query(final Context context, final int disconnectedProfiles, final Callback callback) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { new Probe(context, disconnectedProfiles, callback).start(); }
        });
    }

    private static final class Probe {
        final Context context;
        final int excluded;
        final Callback callback;
        final Handler main = new Handler(Looper.getMainLooper());
        final Map<String, BluetoothDevice> devices = new LinkedHashMap<String, BluetoothDevice>();
        BluetoothAdapter adapter;
        int remaining;
        boolean done, incomplete;
        final Runnable timeout = new Runnable() { @Override public void run() { incomplete = true; finish(); } };

        Probe(Context context, int excluded, Callback callback) {
            this.context = context.getApplicationContext(); this.excluded = excluded; this.callback = callback;
        }
        void start() {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) { incomplete = true; finish(); return; }
            try {
                BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null || !adapter.isEnabled()) { finish(); return; }
                boolean le = false;
                if (Build.VERSION.SDK_INT >= 33) {
                    int support = adapter.isLeAudioSupported();
                    le = support == BluetoothStatusCodes.FEATURE_SUPPORTED;
                    incomplete = !le && support != BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
                }
                remaining = le ? 3 : 2;
                main.postDelayed(timeout, 1500L);
                ask(BluetoothProfile.A2DP); ask(BluetoothProfile.HEADSET);
                if (le) ask(BluetoothProfile.LE_AUDIO);
            } catch (RuntimeException e) { incomplete = true; finish(); }
        }
        void ask(int profile) {
            if ((excluded & (1 << profile)) != 0) { answered(true); return; }
            final class Reply implements BluetoothProfile.ServiceListener {
                boolean received;
                void end(boolean valid) {
                    if (done || received) return;
                    received = true;
                    answered(valid);
                }
                @Override public void onServiceConnected(int id, BluetoothProfile proxy) {
                    boolean valid = false;
                    try {
                        if (done || received) return;
                        List<BluetoothDevice> connected = proxy.getConnectedDevices();
                        if (connected != null) {
                            for (BluetoothDevice device : connected) if (device != null) devices.put(device.getAddress(), device);
                            valid = true;
                        }
                    } catch (RuntimeException ignored) {
                    } finally {
                        // 超时后的迟到回调也必须关闭代理。
                        try { adapter.closeProfileProxy(id, proxy); } catch (RuntimeException ignored) { }
                    }
                    end(valid);
                }
                @Override public void onServiceDisconnected(int id) { end(false); }
            }
            Reply reply = new Reply();
            try { if (!adapter.getProfileProxy(context, reply, profile)) reply.end(false); }
            catch (RuntimeException e) { reply.end(false); }
        }
        void answered(boolean valid) {
            incomplete |= !valid;
            if (--remaining == 0) finish();
        }
        void finish() {
            if (done) return;
            done = true;
            main.removeCallbacks(timeout);
            callback.onResult(new ArrayList<BluetoothDevice>(devices.values()), !incomplete);
        }
    }
    private AudioConnections() { }
}

package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 标准协议驱动: 名字认不出的耳机走这条"保底"路.
 *
 * 只做两件不需要私有协议的事:
 *  1. 用系统公开 profile 问一句"耳机现在是不是连着手表"(自己不开 RFCOMM, 不抢系统 HFP SLC);
 *  2. 听系统转发的标准电量广播: HFP 1.7 的 HF indicator(公开广播) + 隐藏的 BATTERY_LEVEL_CHANGED(兜底).
 *
 * 拿不到就显示未知; caps 里没有 CAP_NOISE, 界面和磁贴会自己把降噪按钮藏掉 —— 不假装能切.
 *
 * 为什么不用 BluetoothDevice.getBatteryLevel(): 它存在但标 @SystemApi, android.jar 里没有,
 * 第三方反射大概率被隐藏 API 拦截, 只能 try/catch 当"读个初值"用.
 * 参考: AOSP BluetoothDevice.java 的 getBatteryLevel()/getMetadata(), Android 蓝牙权限文档.
 */
public class StandardClient implements TwsClient {
    private static final String TAG = "StandardClient";

    /** 隐藏广播: 系统把 HFP BIEV / Apple XAPL / BLE BAS 聚合后的总电量发出来(字符串 @hide, 动态注册可用) */
    private static final String ACTION_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL";

    /**
     * HFP 1.7 HF indicator 变化广播(API 28+ 有, 但常量被标 @hide, android.jar 里没有, 只能用字符串).
     * 规范: http://www.bluetooth.com/specifications/assigned-numbers/hands-free-profile
     */
    private static final String ACTION_HF_IND_VALUE_CHANGED =
            "android.bluetooth.headset.action.HF_INDICATORS_VALUE_CHANGED";
    private static final String EXTRA_HF_IND_ID =
            "android.bluetooth.headset.extra.HF_INDICATORS_IND_ID";
    private static final String EXTRA_HF_IND_VALUE =
            "android.bluetooth.headset.extra.HF_INDICATORS_IND_VALUE";

    private final List<Listener> listeners = new ArrayList<Listener>();
    interface ProfileQuery { void query(Context app, int excluded, AudioConnections.Callback callback); }
    private final ProfileQuery profiles;

    public StandardClient() { this(AudioConnections::query); }
    StandardClient(ProfileQuery profiles) { this.profiles = profiles; }

    private Context app;
    private BluetoothDevice device;
    private String address;
    private String deviceName;
    private volatile boolean connected;
    private volatile boolean closing = true;
    private volatile int session;
    private boolean receiverOn;
    private int disconnectedProfiles, linkRequest;
    private Consumer<Boolean> profileRefresh;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (closing || action == null) return;
            BluetoothDevice d = (BluetoothDevice)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String addr = safeAddress(d);
            if (address == null || !address.equals(addr)) return;

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                if (!connected) {
                    String name = safeName(d);
                    if (name != null) deviceName = name;
                    disconnectedProfiles = 0;
                    checkCurrentLink();
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                // 双模设备单链路断开时可能仍有音频 Profile 在线，交由 AudioConnections 核验。
                if (connected) checkCurrentLink();
            } else if (AudioConnections.profile(action) >= 0) {
                int profile = AudioConnections.profile(action);
                int value = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (value == BluetoothProfile.STATE_CONNECTED) disconnectedProfiles &= ~(1 << profile);
                else if (value == BluetoothProfile.STATE_DISCONNECTED || value == BluetoothProfile.STATE_DISCONNECTING)
                    disconnectedProfiles |= 1 << profile;
                else return;
                // 明确断开的 Profile 不再接受代理中尚未更新的旧 connected 列表。
                checkCurrentLink();
            } else if (ACTION_HF_IND_VALUE_CHANGED.equals(action)) {
                int id = intent.getIntExtra(EXTRA_HF_IND_ID, -1);
                int value = intent.getIntExtra(EXTRA_HF_IND_VALUE, -1);
                if (id == 2) deliverBattery(value); // HF indicator id 2 = 电量
            } else if (ACTION_BATTERY_LEVEL_CHANGED.equals(action)) {
                deliverBattery(intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1));
            }
        }
    };

    @Override
    public void init(Context ctx) {
        app = ctx != null ? ctx.getApplicationContext() : null;
    }

    private static String safeName(BluetoothDevice d) {
        if (d == null) return null;
        try { return d.getName(); }
        catch (SecurityException ignored) { return null; }
    }

    private static String safeAddress(BluetoothDevice d) {
        if (d == null) return null;
        try { return d.getAddress(); }
        catch (SecurityException ignored) { return null; }
    }

    @Override
    public void connect(BluetoothDevice target) {
        disconnect();
        closing = false;
        ++session;
        device = target;
        address = safeAddress(target);
        deviceName = safeName(target);
        disconnectedProfiles = 0;
        ensureReceiver();
        checkCurrentLink();
    }

    private void ensureReceiver() {
        if (receiverOn || app == null) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED");
        f.addAction(ACTION_HF_IND_VALUE_CHANGED);
        f.addAction(ACTION_BATTERY_LEVEL_CHANGED);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(receiver, f);
            receiverOn = true;
        } catch (RuntimeException e) {
            // 个别 ROM 会拦动态注册; 收不到广播就只是没有电量, 不影响连接判断
            Log.w(TAG, "Cannot register Bluetooth battery receiver", e);
        }
    }

    /** 同时查询 A2DP/HFP/LE Audio，单一 Profile 断开不误判其他有效音频连接。 */
    @Override public boolean refreshPublicProfiles(Consumer<Boolean> completed) {
        disconnectedProfiles = 0;
        profileRefresh = completed;
        checkCurrentLink();
        return true;
    }

    private void checkCurrentLink() {
        if (closing || app == null || device == null) return;
        final int request = ++linkRequest, sourceSession = session;
        profiles.query(app, disconnectedProfiles, new AudioConnections.Callback() {
            @Override public void onResult(List<BluetoothDevice> devices, boolean complete) {
                if (closing || sourceSession != session || request != linkRequest) return;
                boolean found = false;
                try {
                    if (devices != null && address != null) {
                        for (BluetoothDevice d : devices) {
                            if (d != null && address.equals(d.getAddress())) found = true;
                        }
                    }
                } catch (SecurityException revoked) { complete = false; }
                Consumer<Boolean> completed = profileRefresh;
                profileRefresh = null;
                if (found) linkUp();
                if (completed != null) completed.accept(found);
                if (closing || found || connected && !complete) return;
                if (connected) {
                    connected = false;
                    fireDisconnected("耳机音频已断开");
                } else {
                    fireError(complete ? "耳机还没连上手表, 请先在系统蓝牙设置里连接" : "无法确认耳机连接状态");
                }
            }
        });
    }

    private void linkUp() {
        if (closing || connected) return;
        connected = true;
        fireConnected(deviceName != null ? deviceName : "耳机");
    }

    private void deliverBattery(int value) {
        if (closing || !connected || value < 0 || value > 100) return;
        Listener[] arr = snapshot();
        for (Listener l : arr) {
            try {
                l.onBattery(value, 0xFF, 0xFF, 0);
            } catch (RuntimeException e) {
                Log.w(TAG, "Battery listener failed", e);
            }
        }
    }

    @Override
    public void disconnect() {
        closing = true;
        profileRefresh = null;
        ++session;
        if (receiverOn && app != null) {
            try {
                app.unregisterReceiver(receiver);
            } catch (RuntimeException e) {
                Log.d(TAG, "Cannot unregister Bluetooth battery receiver", e);
            }
            receiverOn = false;
        }
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void addListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    @Override
    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    @Override
    public void queryBattery() {
        // System battery broadcasts arrive on change. There is no public per-device poll at API 30–34.
    }

    @Override
    public void queryNoise() {
        // 标准协议没有通用的降噪查询, 不假装
    }

    @Override
    public void setNoiseMode(int mode) {
        // 入口已按 caps 隐藏; 真走到这里也只能安静忽略, 别弹错误吓人
    }

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
    public int caps() {
        return CAP_BATTERY_SINGLE;
    }

    @Override
    public int verifLevel() {
        return VERIF_UNTESTED;
    }

    @Override
    public String brandName() {
        return "System Bluetooth";
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
            } catch (RuntimeException e) {
                Log.w(TAG, "Connection listener failed", e);
            }
        }
    }

    private void fireDisconnected(final String reason) {
        for (Listener l : snapshot()) {
            try {
                l.onDisconnected(reason);
            } catch (RuntimeException e) {
                Log.w(TAG, "Disconnection listener failed", e);
            }
        }
    }

    private void fireError(final String msg) {
        for (Listener l : snapshot()) {
            try {
                l.onError(msg);
            } catch (RuntimeException e) {
                Log.w(TAG, "Error listener failed", e);
            }
        }
    }
}

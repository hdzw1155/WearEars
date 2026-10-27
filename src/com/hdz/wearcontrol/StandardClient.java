package com.hdz.wearcontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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

    private Context app;
    private BluetoothAdapter adapter;
    private BluetoothDevice device;
    private String address;
    private String deviceName;
    private boolean connected;
    private boolean receiverOn;
    private boolean triedHeadsetProfile;
    private boolean linkCheckRunning;
    private int lastBattery = -1;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            BluetoothDevice d = (BluetoothDevice)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String addr = d != null ? d.getAddress() : null;
            if (address != null && addr != null && !address.equals(addr)) return;

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                if (!connected) {
                    if (d != null && d.getName() != null) deviceName = d.getName();
                    linkUp();
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                if (connected) {
                    connected = false;
                    fireDisconnected("耳机已断开");
                }
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

    @Override
    public void connect(BluetoothDevice target) {
        device = target;
        if (target != null) {
            address = target.getAddress();
            deviceName = target.getName();
        }
        triedHeadsetProfile = false;
        linkCheckRunning = false;
        ensureReceiver();
        checkCurrentLink();
    }

    private void ensureReceiver() {
        if (receiverOn || app == null) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(ACTION_HF_IND_VALUE_CHANGED);
        f.addAction(ACTION_BATTERY_LEVEL_CHANGED);
        try {
            app.registerReceiver(receiver, f);
            receiverOn = true;
        } catch (Throwable ignored) {
            // 个别 ROM 会拦动态注册; 收不到广播就只是没有电量, 不影响连接判断
        }
    }

    /** 问系统: 目标耳机现在连着没有? 先用 A2DP, 再看 HFP(有些耳机只连了通话通道) */
    private void checkCurrentLink() {
        if (app == null || device == null) {
            fireError("缺少上下文");
            return;
        }
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
        if (a == null) {
            fireError("蓝牙不可用");
            return;
        }
        adapter = a;
        linkCheckRunning = true;
        askProfile(BluetoothProfile.A2DP);
    }

    private void askProfile(int profileId) {
        if (adapter == null) {
            onLinkCheckResult(false);
            return;
        }
        final BluetoothProfile.ServiceListener sl = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                boolean found = false;
                try {
                    List<BluetoothDevice> connectedDevices = proxy.getConnectedDevices();
                    if (connectedDevices != null) {
                        for (BluetoothDevice d : connectedDevices) {
                            if (d != null && address != null && address.equals(d.getAddress())) {
                                found = true;
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 没权限/代理异常都当"没连上"处理
                } finally {
                    try {
                        adapter.closeProfileProxy(profile, proxy);
                    } catch (Throwable ignored) {
                    }
                }
                onLinkCheckResult(found);
            }

            @Override public void onServiceDisconnected(int profile) {
                onLinkCheckResult(false);
            }
        };
        try {
            if (!adapter.getProfileProxy(app, sl, profileId)) onLinkCheckResult(false);
        } catch (Throwable t) {
            onLinkCheckResult(false);
        }
    }

    private void onLinkCheckResult(boolean found) {
        if (found) {
            linkUp();
            return;
        }
        if (!triedHeadsetProfile) {
            triedHeadsetProfile = true;
            askProfile(BluetoothProfile.HEADSET);
            return;
        }
        linkCheckRunning = false;
        if (!connected && device != null) {
            fireError("耳机还没连上手表, 请先在系统蓝牙设置里连接");
        }
    }

    private void linkUp() {
        linkCheckRunning = false;
        if (connected) return;
        connected = true;
        fireConnected(deviceName != null ? deviceName : "耳机");
        readInitialBattery();
    }

    /** 读一次系统聚合电量(隐藏 API, 读不到就算了) */
    private void readInitialBattery() {
        int v = reflectBattery();
        if (v >= 0 && v <= 100) deliverBattery(v);
    }

    private int reflectBattery() {
        if (device == null) return -1;
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object v = m.invoke(device);
            if (v instanceof Integer) {
                int level = (Integer) v;
                if (level >= 0 && level <= 100) return level;
            }
        } catch (Throwable ignored) {
            // 隐藏 API 在 targetSdk 34 下大概率被拦, 正常现象
        }
        return -1;
    }

    private void deliverBattery(int value) {
        if (value < 0 || value > 100) return;
        lastBattery = value;
        Listener[] arr = snapshot();
        for (Listener l : arr) {
            try {
                l.onBattery(value, 0xFF, 0xFF, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void disconnect() {
        if (receiverOn && app != null) {
            try {
                app.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiverOn = false;
        }
        connected = false;
        linkCheckRunning = false;
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
        if (connected) readInitialBattery();
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
        String label = DriverRegistry.brandLabelForName(deviceName);
        return label != null ? label : "标准协议";
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
}

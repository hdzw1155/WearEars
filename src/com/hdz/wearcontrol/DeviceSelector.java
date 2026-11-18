package com.hdz.wearcontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.ParcelUuid;
import java.util.Set;
import java.util.UUID;

/** 自动选择优先使用系统已连接的耳机；显式选择始终固定到用户指定地址。 */
public final class DeviceSelector {
    interface Callback { void onResult(BluetoothDevice device); }
    private static final UUID A2DP_SINK_UUID = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb");
    /**
     * 在已配对设备里挑目标: 名字能被真实驱动认出的优先, 再兜底带 A2DP Sink UUID 的耳机端。
     * 主界面和磁贴后台服务共用此处的选择规则。
     * 手机/平板不是要控的耳机, 直接跳过(否则 "iQOO 12" 这类名字会被当成 vivo 耳机)。
     */
    public static BluetoothDevice findTarget(BluetoothAdapter adapter) {
        return choose(audioDevices(adapter));
    }

    private static BluetoothDevice choose(java.util.List<BluetoothDevice> bonded) {
        // 1) 名字像品牌耳机 + 真收音频(A2DP Sink) = 最可靠
        for (BluetoothDevice d : bonded) {
            if (!isPhone(d) && DriverRegistry.isKnownDevice(d) && hasA2dpSink(d)) return d;
        }
        // 2) 退一步只信名字匹配(有些 ROM 上耳机 SDP 缓存没拉全)
        for (BluetoothDevice d : bonded) {
            if (!isPhone(d) && DriverRegistry.isKnownDevice(d)) return d;
        }
        // 3) 名字认不出, 但确实是收音频设备
        for (BluetoothDevice d : bonded) {
            if (!isPhone(d) && hasA2dpSink(d)) return d;
        }
        return null;
    }

    static void findTarget(final Context context, final BluetoothAdapter adapter, final Callback callback) {
        final String selected = AppPrefs.deviceAddress(context);
        if (!selected.isEmpty()) { callback.onResult(findAddress(adapter, selected)); return; }
        AudioConnections.query(context, 0, new AudioConnections.Callback() {
            @Override public void onResult(java.util.List<BluetoothDevice> connected, boolean complete) {
                BluetoothDevice target = null;
                try {
                    java.util.List<BluetoothDevice> candidates = new java.util.ArrayList<BluetoothDevice>();
                    for (BluetoothDevice device : connected) {
                        if (device.getBondState() == BluetoothDevice.BOND_BONDED && !isPhone(device)) candidates.add(device);
                    }
                    java.util.Collections.sort(candidates, BY_ADDRESS);
                    target = choose(candidates);
                    if (target == null && !candidates.isEmpty()) target = candidates.get(0);
                    if (target == null) target = findTarget(adapter);
                } catch (SecurityException permissionRevoked) { }
                callback.onResult(target);
            }
        });
    }

    private static final java.util.Comparator<BluetoothDevice> BY_ADDRESS = new java.util.Comparator<BluetoothDevice>() {
        @Override public int compare(BluetoothDevice a, BluetoothDevice b) { return a.getAddress().compareTo(b.getAddress()); }
    };

    /** 只列出已配对音频设备，不扫描，不把手机作为控制目标。 */
    public static java.util.List<BluetoothDevice> audioDevices(BluetoothAdapter adapter) {
        java.util.List<BluetoothDevice> result = new java.util.ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bonded = adapter == null ? null : adapter.getBondedDevices();
        if (bonded != null) for (BluetoothDevice d : bonded) {
            if (!isPhone(d) && (DriverRegistry.isKnownDevice(d) || hasA2dpSink(d))) result.add(d);
        }
        java.util.Collections.sort(result, BY_ADDRESS);
        return result;
    }

    public static BluetoothDevice findTarget(Context ctx, BluetoothAdapter adapter) {
        String selected = AppPrefs.deviceAddress(ctx);
        if (selected.isEmpty()) return findTarget(adapter);
        return findAddress(adapter, selected);
    }

    static BluetoothDevice findAddress(BluetoothAdapter adapter, String address) {
        Set<BluetoothDevice> bonded = adapter == null ? null : adapter.getBondedDevices();
        if (bonded != null) for (BluetoothDevice d : bonded) {
            if (address.equals(d.getAddress()) && !isPhone(d)) return d;
        }
        return null;
    }

    private static boolean hasA2dpSink(BluetoothDevice d) {
        ParcelUuid[] uuids = d.getUuids();
        if (uuids == null) return false;
        for (ParcelUuid u : uuids) {
            if (u.getUuid().equals(A2DP_SINK_UUID)) return true;
        }
        return false;
    }

    private static boolean isPhone(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
    }

    private DeviceSelector() { }
}

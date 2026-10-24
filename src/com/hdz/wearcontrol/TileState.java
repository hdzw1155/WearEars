package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.List;

/** 磁贴共用的状态探测: 判断耳机是否真的挂在手表系统上(多品牌通用) */
final class TileState {

    private TileState() {
    }

    /** 实时状态文字: last_ok 兜底 -> MAC反射 -> GATT 列表, 单一手段都会误判 */
    static String liveStatus(Context ctx) {
        android.bluetooth.BluetoothAdapter adapter = null;
        BluetoothManager bm =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return "蓝牙关";

        SharedPreferences cfg = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        // 刚成功通讯过(切档/开App连上)也算连接: 双链路空闲时系统查询会漏判
        long lastOk = cfg.getLong("tile_last_ok", 0);
        if (System.currentTimeMillis() - lastOk < 30 * 60 * 1000L) return "已连接";
        String mac = cfg.getString("tile_mac", null);
        if (mac != null) {
            try {
                BluetoothDevice d = adapter.getRemoteDevice(mac);
                Method m = d.getClass().getMethod("isConnected");
                Object r = m.invoke(d);
                if (r instanceof Boolean && (Boolean) r) return "已连接";
            } catch (Exception ignored) {
                // 反射失败走 GATT 查询
            }
        }
        List<BluetoothDevice> connected = bm.getConnectedDevices(BluetoothProfile.GATT);
        return findEarbudIn(connected) ? "已连接" : "未连接";
    }

    /** 在已连接 GATT 设备里找已知品牌耳机(与 ClientFactory 的判定词保持一致) */
    static boolean findEarbudIn(List<BluetoothDevice> connected) {
        if (connected == null) return false;
        for (BluetoothDevice d : connected) {
            if (ClientFactory.create(d) != null) return true;
        }
        return false;
    }
}

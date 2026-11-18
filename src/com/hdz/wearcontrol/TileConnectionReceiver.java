package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 系统连接事件只触发重绘，连接结论由本次系统查询产生。 */
public final class TileConnectionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try { update(context, intent); }
        catch (SecurityException permissionRevoked) { TileStore.push(context); }
    }

    private void update(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        // 导出接收器也只接受框架受保护的连接广播，显式投递的其他 action 无副作用。
        if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                && !BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                && AudioConnections.profile(action) < 0) return;
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device != null && !device.getAddress().equals(TileStore.deviceAddress(context))) return;
        // 活跃控制器自行监听断连；刷新磁贴无需实例化控制器或启动蓝牙会话。
        TileStore.push(context);
    }
}

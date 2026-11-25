package com.hdz.wearcontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** 系统连接事件只触发重绘，连接结论由本次系统查询产生。 */
public final class TileConnectionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // 手机端没有 Wear OS 磁贴，直接忽略系统蓝牙广播避免无谓后台唤醒与 IPC 耗电。
        if (!DeviceForm.isWatch(context)) return;
        try { update(context, intent); }
        catch (SecurityException permissionRevoked) { TileStore.pushNow(context); }
        catch (RuntimeException e) { Log.w("TileConnRx", "Ignoring invalid broadcast", e); }
    }

    private void update(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        int profile = AudioConnections.profile(action);
        if (profile == BluetoothProfile.LE_AUDIO && Build.VERSION.SDK_INT < 33) return;
        // 导出接收器也只接受框架受保护的连接广播，显式投递的其他 action 无副作用。
        if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                && !BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                && profile < 0) return;
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device != null) {
            String addr = device.getAddress();
            if (addr == null) return;
            boolean isConnectEvent = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                    || (profile >= 0 && intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                            == BluetoothProfile.STATE_CONNECTED);
            if (isConnectEvent) {
                String pinned = AppPrefs.deviceAddress(context);
                if (!pinned.isEmpty() && !pinned.equals(addr)) return;
            } else {
                String stored = TileStore.deviceAddress(context);
                if (stored.isEmpty() || !addr.equals(stored)) return;
            }
        }
        // 活跃控制器自行监听断连；刷新磁贴无需实例化控制器或启动蓝牙会话。
        TileStore.pushNow(context);
    }
}

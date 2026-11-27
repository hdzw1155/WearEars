package com.hdz.wearcontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Bluetooth events update the cached Quick Settings link and request a Wear tile redraw. */
public final class TileConnectionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
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
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                TileStore.noteQuickSettingsConnection(context, null, false);
            }
        }
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
            if (isConnectEvent || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                TileStore.noteQuickSettingsConnection(context, addr, isConnectEvent);
            }
        }
        // 活跃控制器自行监听断连；刷新磁贴无需实例化控制器或启动蓝牙会话。
        TileStore.pushNow(context);
    }
}

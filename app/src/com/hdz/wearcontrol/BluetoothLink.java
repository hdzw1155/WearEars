package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import java.util.List;

/** 当前控制链路或系统音频连接才是连接证据，历史电量不是。 */
final class BluetoothLink {
    static final int UNKNOWN = LinkState.UNKNOWN, CONNECTED = LinkState.CONNECTED, DISCONNECTED = LinkState.DISCONNECTED;
    interface Callback { void onResult(int connection); }

    static void query(final Context context, final String address, final Callback callback) {
        if (address == null || address.isEmpty()) {
            callback.onResult(UNKNOWN);
            return;
        }
        AudioConnections.query(context, 0, new AudioConnections.Callback() {
            @Override public void onResult(List<BluetoothDevice> devices, boolean complete) {
                // StandardClient 的 connected 标志不能替代本次系统查询。
                if (ControlHub.hasActiveLink(address)) { callback.onResult(CONNECTED); return; }
                try {
                    for (BluetoothDevice device : devices) {
                        if (address.equals(device.getAddress())) { callback.onResult(CONNECTED); return; }
                    }
                } catch (SecurityException revoked) { callback.onResult(UNKNOWN); return; }
                callback.onResult(complete ? DISCONNECTED : UNKNOWN);
            }
        });
    }
    private BluetoothLink() { }
}

package com.hdz.wearcontrol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

final class BluetoothAccess {
    static boolean connect(Context ctx) { return granted(ctx, Manifest.permission.BLUETOOTH_CONNECT); }
    static boolean cancelDiscovery(Context ctx) { return granted(ctx, Manifest.permission.BLUETOOTH_SCAN); }
    private static boolean granted(Context ctx, String permission) {
        return Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private BluetoothAccess() { }
}

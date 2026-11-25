package android.content;

import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.List;

public class Context {
    public static final int RECEIVER_EXPORTED = 2;
    public static final String BLUETOOTH_SERVICE = "bluetooth";

    public final PackageManager packages = new PackageManager();
    public final List<BroadcastReceiver> receivers = new ArrayList<>();
    public BluetoothManager bluetoothManager = new BluetoothManager();
    public int bluetoothPermission = PackageManager.PERMISSION_GRANTED;
    public SharedPreferences preferences;
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!name.equals("cfg") || mode != 0) throw new AssertionError("Unexpected preference file");
        return preferences;
    }
    public PackageManager getPackageManager() { return packages; }
    public String getPackageName() { return "com.hdz.wearcontrol"; }
    public Context getApplicationContext() { return this; }
    public int checkSelfPermission(String permission) { return bluetoothPermission; }
    public Object getSystemService(String name) {
        return BLUETOOTH_SERVICE.equals(name) ? bluetoothManager : null;
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (receiver != null && !receivers.contains(receiver)) receivers.add(receiver);
        return null;
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return registerReceiver(receiver, filter);
    }
    public void unregisterReceiver(BroadcastReceiver receiver) { receivers.remove(receiver); }
}

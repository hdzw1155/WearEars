package android.bluetooth;

import android.content.Context;

public class BluetoothDevice {
    public String getAddress() { return "00:11:22:33:44:55"; }
    public String getName() { return "Standard headset"; }
    public final BluetoothGatt stack = new BluetoothGatt();
    public BluetoothGatt connectGatt(Context ctx, boolean auto, BluetoothGattCallback callback, int transport) {
        if (auto || transport != 2) throw new AssertionError("Expected direct LE connection");
        return stack;
    }
}

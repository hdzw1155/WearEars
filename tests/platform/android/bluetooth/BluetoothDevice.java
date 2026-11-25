package android.bluetooth;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

public class BluetoothDevice implements Parcelable {
    public static final String ACTION_ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED";
    public static final String ACTION_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED";
    public static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    public static final String EXTRA_TRANSPORT = "android.bluetooth.device.extra.TRANSPORT";
    public static final int TRANSPORT_AUTO = 0;
    public static final int TRANSPORT_BREDR = 1;
    public static final int TRANSPORT_LE = 2;

    public String address = "00:11:22:33:44:55";
    public String name = "Standard headset";
    public boolean throwOnGetName;
    public boolean throwOnGetAddress;
    public final BluetoothGatt stack = new BluetoothGatt();

    public BluetoothDevice() { }
    public BluetoothDevice(String address, String name) {
        this.address = address;
        this.name = name;
    }

    public String getAddress() {
        if (throwOnGetAddress) throw new SecurityException("BLUETOOTH_CONNECT revoked");
        return address;
    }
    public String getName() {
        if (throwOnGetName) throw new SecurityException("BLUETOOTH_CONNECT revoked");
        return name;
    }
    public BluetoothGatt connectGatt(Context ctx, boolean auto, BluetoothGattCallback callback, int transport) {
        if (auto || transport != 2) throw new AssertionError("Expected direct LE connection");
        return stack;
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { }
}

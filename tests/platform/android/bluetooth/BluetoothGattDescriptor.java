package android.bluetooth;

public class BluetoothGattDescriptor {
    public static final byte[] ENABLE_NOTIFICATION_VALUE = {1, 0}, ENABLE_INDICATION_VALUE = {2, 0};
    byte[] value;
    public boolean setValue(byte[] data) { value = data; return true; }
}

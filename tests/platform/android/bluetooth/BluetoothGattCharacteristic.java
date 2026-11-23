package android.bluetooth;

import java.util.UUID;

public class BluetoothGattCharacteristic {
    private final UUID id;
    private final int flags;
    private byte[] value;
    public BluetoothGattDescriptor cccd = new BluetoothGattDescriptor();
    public BluetoothGattCharacteristic(UUID id, int flags) { this.id = id; this.flags = flags; }
    public UUID getUuid() { return id; }
    public int getProperties() { return flags; }
    public BluetoothGattDescriptor getDescriptor(UUID key) {
        return UUID.fromString("00002902-0000-1000-8000-00805f9b34fb").equals(key) ? cccd : null;
    }
    public byte[] getValue() { return value; }
    public boolean setValue(byte[] data) { value = data; return true; }
    public void setWriteType(int type) { }
}

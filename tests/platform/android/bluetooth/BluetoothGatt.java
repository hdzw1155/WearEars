package android.bluetooth;

import java.util.ArrayList;
import java.util.List;

public class BluetoothGatt {
    public final List<BluetoothGattService> services = new ArrayList<>();
    public final List<byte[]> writes = new ArrayList<>();
    public boolean subscribed, closed, rejectWrite;
    public byte[] descriptorValue;
    public int discoveries, descriptorStarts, modernWrites, legacyWrites;
    public boolean discoverServices() { discoveries++; return true; }
    public List<BluetoothGattService> getServices() { return services; }
    public boolean setCharacteristicNotification(BluetoothGattCharacteristic c, boolean value) {
        subscribed = value;
        return true;
    }
    public int writeDescriptor(BluetoothGattDescriptor d, byte[] value) {
        descriptorStarts++; descriptorValue = value.clone(); return 0;
    }
    public boolean writeDescriptor(BluetoothGattDescriptor d) { return writeDescriptor(d, d.value) == 0; }
    public int writeCharacteristic(BluetoothGattCharacteristic c, byte[] bytes, int type) {
        modernWrites++;
        if (rejectWrite) return 1;
        writes.add(bytes.clone());
        return 0;
    }
    public boolean writeCharacteristic(BluetoothGattCharacteristic c) {
        legacyWrites++;
        if (rejectWrite) return false;
        writes.add(c.getValue().clone());
        return true;
    }
    public void disconnect() { }
    public void close() { closed = true; }
}

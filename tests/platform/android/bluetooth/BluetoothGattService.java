package android.bluetooth;

import java.util.*;

public class BluetoothGattService {
    private final UUID id;
    public final Map<UUID, BluetoothGattCharacteristic> characteristics = new HashMap<>();
    public BluetoothGattService(UUID id) { this.id = id; }
    public UUID getUuid() { return id; }
    public BluetoothGattCharacteristic getCharacteristic(UUID key) { return characteristics.get(key); }
}

package android.bluetooth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BluetoothLeAudio implements BluetoothProfile {
    public static final String ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED =
            "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED";
    public static final int GROUP_ID_INVALID = -1;

    public final List<BluetoothDevice> connected = new ArrayList<>();
    public final Map<String, Integer> groupIds = new HashMap<>();
    public final Map<Integer, BluetoothDevice> leadDevices = new HashMap<>();

    @Override public List<BluetoothDevice> getConnectedDevices() { return new ArrayList<>(connected); }
    @Override public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) { return getConnectedDevices(); }
    @Override public int getConnectionState(BluetoothDevice device) {
        return connected.contains(device) ? STATE_CONNECTED : STATE_DISCONNECTED;
    }
    public int getGroupId(BluetoothDevice device) {
        if (device == null) return GROUP_ID_INVALID;
        Integer id = groupIds.get(device.getAddress());
        return id == null ? GROUP_ID_INVALID : id;
    }
    public BluetoothDevice getConnectedGroupLeadDevice(int groupId) {
        return leadDevices.get(groupId);
    }
}

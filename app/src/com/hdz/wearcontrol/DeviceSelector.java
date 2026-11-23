package com.hdz.wearcontrol;

import android.bluetooth.*;
import android.content.Context;
import android.os.ParcelUuid;
import java.util.*;

final class DeviceSelector {
    private static final UUID SINK = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb");
    interface Callback { void selected(Selection.Result result, BluetoothDevice device); }

    static void select(Context ctx, BluetoothAdapter adapter, String pin, Callback callback) {
        if (!pin.isEmpty()) {
            resolve(adapter, pin, Collections.emptyList(), true, callback);
        } else AudioConnections.query(ctx, 0, (devices, complete) -> resolve(adapter, pin, devices, complete, callback));
    }
    private static void resolve(BluetoothAdapter adapter, String pin, List<BluetoothDevice> active,
                                boolean complete, Callback callback) {
        try {
            Map<String, BluetoothDevice> byAddress = new HashMap<>();
            List<Selection.Peer> peers = new ArrayList<>();
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) { callback.selected(Selection.Result.INCOMPLETE, null); return; }
            for (BluetoothDevice d : bonded) {
                String address = d.getAddress();
                byAddress.put(address, d);
                peers.add(new Selection.Peer(address, true, phone(d), active.contains(d),
                        DriverRegistry.known(d.getName()), sink(d)));
            }
            Selection choice = Selection.choose(pin, complete, peers);
            callback.selected(choice.result, byAddress.get(choice.address));
        } catch (RuntimeException inaccessible) { callback.selected(Selection.Result.INCOMPLETE, null); }
    }
    static List<BluetoothDevice> audioDevices(BluetoothAdapter adapter) {
        return audioDevices(adapter, Collections.emptyList());
    }
    static List<BluetoothDevice> audioDevices(BluetoothAdapter adapter, List<BluetoothDevice> active) {
        List<BluetoothDevice> list = new ArrayList<>();
        if (adapter != null && adapter.getBondedDevices() != null) {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (!phone(d) && (active.contains(d) || sink(d) || DriverRegistry.known(d.getName()))) list.add(d);
            }
        }
        list.sort(Comparator.comparing(BluetoothDevice::getAddress));
        return list;
    }
    private static boolean phone(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
    }
    private static boolean sink(BluetoothDevice d) {
        ParcelUuid[] ids = d.getUuids();
        if (ids != null) for (ParcelUuid id : ids) if (id != null && SINK.equals(id.getUuid())) return true;
        return false;
    }
    private DeviceSelector() { }
}

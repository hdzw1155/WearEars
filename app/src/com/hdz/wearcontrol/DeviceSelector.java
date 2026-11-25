package com.hdz.wearcontrol;

import android.bluetooth.*;
import android.content.Context;
import android.os.ParcelUuid;
import java.util.*;

final class DeviceSelector {
    private static final Set<UUID> AUDIO_SINK_UUIDS = Set.of(
            UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb"), // A2DP Sink
            UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb"), // HFP
            UUID.fromString("0000184e-0000-1000-8000-00805f9b34fb"), // LE Audio ASCS
            UUID.fromString("00001850-0000-1000-8000-00805f9b34fb"), // LE Audio PACS
            UUID.fromString("0000184d-0000-1000-8000-00805f9b34fb")  // LE Audio CSIS
    );
    interface Callback { void selected(Selection.Result result, BluetoothDevice device); }

    static void select(Context ctx, BluetoothAdapter adapter, String pin, Callback callback) {
        if (pin != null && !pin.isEmpty()) {
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
            Set<String> activeAddresses = addresses(active);
            for (BluetoothDevice d : bonded) {
                if (d == null) continue;
                String address = d.getAddress();
                if (address == null || address.isEmpty()) continue;
                byAddress.put(address, d);
                peers.add(new Selection.Peer(address, true, phone(d), activeAddresses.contains(address),
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
            Set<String> activeAddresses = addresses(active);
            Set<String> seen = new HashSet<>();
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d == null) continue;
                String address = safeAddress(d);
                if (address.isEmpty() || !seen.add(address)) continue;
                if (!phone(d) && (activeAddresses.contains(address) || sink(d) || DriverRegistry.known(safeName(d)))) {
                    list.add(d);
                }
            }
        }
        list.sort(Comparator.comparing(DeviceSelector::safeAddress));
        return list;
    }
    private static Set<String> addresses(List<BluetoothDevice> devices) {
        if (devices == null || devices.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (BluetoothDevice d : devices) {
            String addr = safeAddress(d);
            if (!addr.isEmpty()) out.add(addr);
        }
        return out;
    }
    private static String safeAddress(BluetoothDevice d) {
        if (d == null) return "";
        try {
            String addr = d.getAddress();
            return addr == null ? "" : addr;
        } catch (SecurityException ignored) { return ""; }
    }
    private static String safeName(BluetoothDevice d) {
        if (d == null) return null;
        try { return d.getName(); }
        catch (SecurityException ignored) { return null; }
    }
    private static boolean phone(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
    }
    private static boolean sink(BluetoothDevice d) {
        ParcelUuid[] ids = d.getUuids();
        if (ids != null) for (ParcelUuid id : ids) if (id != null && AUDIO_SINK_UUIDS.contains(id.getUuid())) return true;
        return false;
    }
    private DeviceSelector() { }
}

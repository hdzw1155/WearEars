package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

/** Every included factory is a typed constructor reference in the generated InstalledDrivers class. */
final class DriverRegistry {
    private static DriverModule match(String name) {
        if (name == null || name.isEmpty()) return null;
        DriverModule found = null;
        for (DriverModule module : InstalledDrivers.ALL) {
            if (module.matches(name)) {
                if (found != null) return null;
                found = module;
            }
        }
        return found;
    }
    static boolean known(String name) { return match(name) != null; }
    static TwsClient create(Context app, BluetoothDevice device) {
        DriverModule module = match(device.getName());
        TwsClient client = module == null ? new StandardClient() : new WireSession(module.create());
        client.init(app);
        return client;
    }
    private DriverRegistry() { }
}

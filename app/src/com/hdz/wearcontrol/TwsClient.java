package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import java.util.function.Consumer;

interface TwsClient extends Controls {
    void connect(BluetoothDevice device);
    void disconnect();
    boolean isConnected();
    void addListener(Listener listener);
    void removeListener(Listener listener);
    void init(Context context);
    default void execute(Runnable action) { action.run(); }
    default boolean needsDiscoveryPermission() { return false; }
    default void changeNoise(int mode, Runnable flushed) { }
    default boolean privateLink() { return false; }
    default boolean refreshPublicProfiles(Consumer<Boolean> completed) { return false; }
}

package com.hdz.wearcontrol;

import android.bluetooth.*;
import java.io.*;
import java.util.UUID;

final class AndroidSockets implements RfcommLink.Sockets {
    private final BluetoothDevice device;
    AndroidSockets(BluetoothDevice device) { this.device = device; }
    public RfcommLink.Socket create(UUID service, boolean insecure, int channel) throws Exception {
        final BluetoothSocket socket;
        if (channel != 0) {
            // Android exposes fixed RFCOMM channels only on some builds. SDP remains the first route.
            socket = (BluetoothSocket) BluetoothDevice.class.getMethod("createRfcommSocket", int.class)
                    .invoke(device, channel);
        } else if (insecure) socket = device.createInsecureRfcommSocketToServiceRecord(service);
        else socket = device.createRfcommSocketToServiceRecord(service);
        return new RfcommLink.Socket() {
            public void connect() throws IOException { socket.connect(); }
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public void close() throws IOException { socket.close(); }
        };
    }
}

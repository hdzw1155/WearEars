package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import java.util.UUID;

/** 按合法回包开放电量和三态，厂商协议尚未在本项目真机验证。 */
final class TechnicsClient extends RfcommClient {
    private TechnicsProtocol.Decoder decoder;
    private final int[] battery = {255, 255, 255};
    private volatile boolean noiseConfirmed;
    private int change;

    @Override protected UUID[] services(BluetoothDevice device) {
        return new UUID[]{UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"),
                UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")};
    }
    @Override protected void opened() {
        decoder = new TechnicsProtocol.Decoder();
        java.util.Arrays.fill(battery, 255);
        noiseConfirmed = false;
        change = 0;
        queryNoise();
        queryBattery();
    }
    @Override protected void received(byte[] data) {
        for (byte[] frame : decoder.feed(data)) {
            int mode = TechnicsProtocol.mode(frame);
            if (mode >= 0) {
                noiseConfirmed = true;
                ready();
                for (Listener l : listeners) l.onNoiseMode(mode);
            }
            int[] value = TechnicsProtocol.battery(frame);
            if (value != null) {
                battery[value[0]] = value[1];
                ready();
                for (Listener l : listeners) l.onBattery(battery[0], battery[1], battery[2], -1);
            }
        }
    }
    @Override public void queryBattery() {
        schedule(new Runnable() { @Override public void run() {
            write(TechnicsProtocol.command(0x0CD6, (byte) 0));
        }}, 0);
        schedule(new Runnable() { @Override public void run() {
            write(TechnicsProtocol.command(0x0CD6, (byte) 1));
        }}, 120);
        schedule(new Runnable() { @Override public void run() {
            write(TechnicsProtocol.command(0x0040));
        }}, 240);
    }
    @Override public void queryNoise() {
        schedule(new Runnable() { @Override public void run() { write(TechnicsProtocol.command(0x000A)); }}, 0);
    }
    @Override public void setNoiseMode(final int mode) {
        schedule(new Runnable() { @Override public void run() {
            final byte[][] commands = TechnicsProtocol.noiseCommands(mode);
            if (!noiseConfirmed || commands == null) return;
            final int id = ++change;
            for (int i = 0; i < commands.length; i++) {
                final byte[] command = commands[i];
                schedule(new Runnable() { @Override public void run() {
                    if (id == change) write(command);
                }}, i * 120L);
            }
            schedule(new Runnable() { @Override public void run() {
                if (id == change) queryNoise();
            }}, commands.length * 120L + 400);
        }}, 0);
    }
    @Override public int caps() { return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0); }
    @Override public String brandName() { return "Technics"; }
}

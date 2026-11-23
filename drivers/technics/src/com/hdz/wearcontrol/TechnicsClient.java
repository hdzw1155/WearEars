package com.hdz.wearcontrol;

import java.util.UUID;

/** 按合法回包开放电量和三态，厂商协议尚未在本项目真机验证。 */
final class TechnicsClient extends WireDriver {
    private static final long FRAME_INTERVAL_MS = 120L;
    private TechnicsProtocol.Decoder decoder;
    private final int[] battery = {255, 255, 255};
    private volatile boolean noiseConfirmed;
    private int change;

    @Override protected UUID[] services(UUID[] advertised) {
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
                events().onNoiseMode(mode);
            }
            int[] value = TechnicsProtocol.battery(frame);
            if (value != null) {
                battery[value[0]] = value[1];
                ready();
                events().onBattery(battery[0], battery[1], battery[2], -1);
            }
        }
    }
    @Override public void queryBattery() {
        schedule(new Runnable() { @Override public void run() {
            writeSeries(new byte[][]{
                    TechnicsProtocol.command(0x0CD6, (byte) 0),
                    TechnicsProtocol.command(0x0CD6, (byte) 1),
                    TechnicsProtocol.command(0x0040)
            }, 0, 0, null);
        }}, 0);
    }
    @Override public void queryNoise() {
        schedule(new Runnable() { @Override public void run() { write(TechnicsProtocol.command(0x000A)); }}, 0);
    }
    @Override public void changeNoise(final int mode, Runnable flushed) {
        schedule(new Runnable() { @Override public void run() {
            final byte[][] commands = TechnicsProtocol.noiseCommands(mode);
            if (!noiseConfirmed || commands == null) return;
            writeSeries(commands, 0, ++change, flushed);
        }}, 0);
    }

    /** Change IDs keep an older mode series from continuing after a newer tap. */
    private void writeSeries(final byte[][] commands, final int index, final int changeId, Runnable flushed) {
        if (changeId != 0 && changeId != change) return;
        writeThen(commands[index], new Runnable() { @Override public void run() {
            if (changeId != 0 && changeId != change) return;
            if (index + 1 < commands.length) {
                schedule(new Runnable() { @Override public void run() {
                    writeSeries(commands, index + 1, changeId, flushed);
                }}, FRAME_INTERVAL_MS);
            } else if (changeId != 0) {
                flushed.run();
                schedule(new Runnable() { @Override public void run() {
                    if (changeId == change) queryNoise();
                }}, FRAME_INTERVAL_MS + 400L);
            }
        }});
    }
    @Override public int caps() { return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0); }
    @Override public String brandName() { return "Technics"; }
    @Override public int potentialCaps() { return caps() | CAP_NOISE; }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            return TechnicsProtocol.matches(name);
        }
        @Override public WireDriver create() { return new TechnicsClient(); }
    }

}

package com.hdz.wearcontrol;

import java.util.UUID;

/** RoseLink 已知 RS/V2 型号：先订阅、再查询，以有效状态包确认连接。 */
public final class RoseLinkClient extends WireDriver {
    private static final UUID SPP = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3931");
    private static final UUID SERVICE = UUID.fromString("000000fe-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("000000f1-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("000000f2-0000-1000-8000-00805f9b34fb");
    private RoseProtocol.Decoder decoder;
    private volatile boolean noiseConfirmed;
    private int family, sequence;
    private volatile String name;
    private long lastQuery;
    private final Runnable confirm = new Runnable() {
        @Override public void run() { queryBattery(); }
    };
    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            if (!isConnected()) fail("耳机未返回 RoseLink 状态");
        }
    };

    @Override protected String logTag() { return "RoseLink"; }
    @Override protected String unnamedDevice() { return ""; }
    @Override protected String connectionFailure() { return "无法打开 RoseLink BLE 通道"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{SPP}; }
    @Override protected int bleFragmentDelay() { return 40; }
    @Override protected void openFallback() { openBle(SERVICE, NOTIFY, WRITE, 0, 5000); }
    @Override protected void prepare() {
        name = deviceName();
        family = RoseProtocol.familyForName(name);
        noiseConfirmed = false;
        sequence = 0;
        lastQuery = -1;
        decoder = new RoseProtocol.Decoder(family);
        if (family < 0) fail("该型号的 RoseLink 协议尚未确认");
    }
    @Override protected void opened() {
        schedule(timeout, 4000);
        queryBattery();
    }

    @Override protected void received(byte[] bytes) {
        for (RoseProtocol.Item item : decoder.feed(bytes, bytes.length)) {
            if (item.type == 0x0C) {
                int[] value = RoseProtocol.battery(family, item.value);
                if (value == null) continue;
                markConnected();
                events().onBattery(value[0], value[1], value[2], value[3]);
            } else if (item.type == 9 && item.value.length == 1) {
                int mode = RoseProtocol.mode(item.value[0] & 255);
                if (mode < 0) continue;
                noiseConfirmed = RoseProtocol.supportsNoise(name);
                markConnected();
                if (noiseConfirmed) events().onNoiseMode(mode);
            }
        }
    }

    private void markConnected() {
        if (isConnected()) return;
        cancel(timeout);
        ready();
    }

    @Override public synchronized void queryBattery() {
        if (!transportOpen()) return;
        long now = clockMillis();
        // 电量和模式共用 getInfo，合并控制器同一轮的两次读取。
        if (lastQuery >= 0 && now - lastQuery < 300) return;
        lastQuery = now;
        write(RoseProtocol.query(family, sequence++));
    }
    @Override public void queryNoise() { queryBattery(); }
    @Override public synchronized void changeNoise(int mode, Runnable flushed) {
        if (!isConnected() || !noiseConfirmed) return;
        int value = mode == MODE_ANC ? 1 : mode == MODE_OFF ? 2 : mode == MODE_TRANS ? 3 : -1;
        if (value < 0) return;
        writeThen(RoseProtocol.encode(family, sequence++, new byte[]{9, (byte) value}), () -> {
            flushed.run();
            cancel(confirm);
            schedule(confirm, 700);
        });
    }
    @Override public int caps() { return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0); }
    @Override public int verifLevel() { return VERIF_UNTESTED; }
    @Override public String brandName() { return "弱水时砂"; }
    @Override public int potentialCaps() { return caps() | (name == null || RoseProtocol.supportsNoise(name) ? CAP_NOISE : 0); }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            return RoseProtocol.familyForName(name) >= 0;
        }
        @Override public WireDriver create() { return new RoseLinkClient(); }
    }

}

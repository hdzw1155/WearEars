package com.hdz.wearcontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** RoseLink 已知 RS/V2 型号：先订阅、再查询，以有效状态包确认连接；绝不发 32(双设备) / 2B(编解码) 重启指令。 */
public final class RoseLinkClient extends WireDriver {
    private static final UUID SPP = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3931");
    private static final UUID SERVICE = UUID.fromString("000000fe-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("000000f1-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("000000f2-0000-1000-8000-00805f9b34fb");

    private static final OptionItem[] ANC_LEVEL_OPTIONS = {
            new OptionItem(1, "轻度降噪", "Light", OptionItem.ROLE_DEFAULT),
            new OptionItem(3, "中度降噪", "Moderate", OptionItem.ROLE_DEFAULT),
            new OptionItem(5, "深度降噪", "Deep", OptionItem.ROLE_DEFAULT),
            new OptionItem(RoseProtocol.WIND_WIRE, "抗风噪", "Wind", OptionItem.ROLE_WIND)
    };
    private static final FeatureDescriptor ANC_LEVEL_SPEC = FeatureDescriptor.choice(
            FeatureId.ANC_LEVEL, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false, ANC_LEVEL_OPTIONS);
    private static final FeatureDescriptor LOW_LATENCY_SPEC = FeatureDescriptor.toggle(
            FeatureId.LOW_LATENCY, VERIF_UNTESTED, false);
    private static final FeatureDescriptor EQUALIZER_SPEC = FeatureDescriptor.choice(
            FeatureId.EQUALIZER, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            0, false, OptionItem.fromPresets(RoseProtocol.EQ_PRESETS));
    private static final FeatureDescriptor WEAR_DETECT_SPEC = FeatureDescriptor.toggle(
            FeatureId.WEAR_DETECTION, VERIF_UNTESTED, false);

    private RoseProtocol.Decoder decoder;
    private volatile boolean noiseConfirmed, ancLevelConfirmed, lowLatencyConfirmed, eqConfirmed, wearDetectConfirmed;
    private int family, sequence;
    private int lastNoiseRaw = -1, lastAncDepth = -1;
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
        noiseConfirmed = ancLevelConfirmed = lowLatencyConfirmed = eqConfirmed = wearDetectConfirmed = false;
        lastNoiseRaw = lastAncDepth = -1;
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
            if (item.type == RoseProtocol.ITEM_BATTERY) {
                int[] value = RoseProtocol.battery(family, item.value);
                if (value == null) continue;
                markConnected();
                events().onBattery(value[0], value[1], value[2], value[3]);
            } else if (item.type == RoseProtocol.ITEM_NOISE && item.value.length == 1) {
                int raw = item.value[0] & 255;
                int mode = RoseProtocol.mode(raw);
                boolean wind = RoseProtocol.isWind(raw);
                if (mode < 0 && !wind) continue;
                noiseConfirmed = RoseProtocol.supportsNoise(name);
                markConnected();
                if (noiseConfirmed) {
                    lastNoiseRaw = raw;
                    events().onNoiseMode(wind ? MODE_ANC : mode);
                    if (wind) {
                        ancLevelConfirmed = true;
                        confirmFeature(ANC_LEVEL_SPEC, RoseProtocol.WIND_WIRE);
                    } else if (mode == MODE_ANC && lastAncDepth > 0) {
                        ancLevelConfirmed = true;
                        confirmFeature(ANC_LEVEL_SPEC, lastAncDepth);
                    }
                }
            } else if (item.type == RoseProtocol.ITEM_ANC_DEPTH && item.value.length == 1) {
                int depth = item.value[0] & 255;
                if (RoseProtocol.supportsNoise(name) && RoseProtocol.isAncDepth(depth)) {
                    lastAncDepth = depth;
                    ancLevelConfirmed = true;
                    markConnected();
                    if (lastNoiseRaw == RoseProtocol.WIND_WIRE) {
                        confirmFeature(ANC_LEVEL_SPEC, RoseProtocol.WIND_WIRE);
                    } else {
                        confirmFeature(ANC_LEVEL_SPEC, depth);
                    }
                }
            } else if (item.type == RoseProtocol.ITEM_GAME && item.value.length == 1) {
                int game = item.value[0] & 255;
                if (game == 0 || game == 1) {
                    lowLatencyConfirmed = true;
                    markConnected();
                    confirmFeature(LOW_LATENCY_SPEC, game);
                }
            } else if (item.type == RoseProtocol.ITEM_EQ && item.value.length == 1) {
                int eq = item.value[0] & 255;
                if (RoseProtocol.isEqPreset(eq)) {
                    eqConfirmed = true;
                    markConnected();
                    confirmFeature(EQUALIZER_SPEC, eq);
                    events().onEqualizer(eq);
                }
            } else if (item.type == RoseProtocol.ITEM_IN_EAR && item.value.length == 1) {
                Boolean inEar = RoseProtocol.inEarFromWire(item.value[0] & 255);
                if (inEar != null) {
                    wearDetectConfirmed = true;
                    markConnected();
                    confirmFeature(WEAR_DETECT_SPEC, inEar ? 1 : 0);
                }
            }
        }
    }

    private void markConnected() {
        if (isConnected()) return;
        cancel(timeout);
        lastQuery = -1;
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
        writeThen(RoseProtocol.encode(family, sequence++, new byte[]{RoseProtocol.ITEM_NOISE, (byte) value}), () -> {
            if (flushed != null) flushed.run();
            cancel(confirm);
            schedule(confirm, 700);
        });
    }

    @Override public SoundPreset[] equalizerPresets() {
        return eqConfirmed ? RoseProtocol.EQ_PRESETS.clone() : new SoundPreset[0];
    }

    @Override public void queryEqualizer() { queryBattery(); }

    @Override public void setEqualizer(int preset) {
        writeFeature(FeatureId.EQUALIZER, preset, null);
    }

    @Override public FeatureDescriptor[] featureDescriptors() {
        List<FeatureDescriptor> list = new ArrayList<>();
        if (ancLevelConfirmed) list.add(ANC_LEVEL_SPEC);
        if (lowLatencyConfirmed) list.add(LOW_LATENCY_SPEC);
        if (eqConfirmed) list.add(EQUALIZER_SPEC);
        if (wearDetectConfirmed) list.add(WEAR_DETECT_SPEC);
        return list.toArray(new FeatureDescriptor[0]);
    }

    @Override public void queryFeature(FeatureId id) {
        if (id != null && isConnected()) queryBattery();
    }

    @Override public synchronized void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (!isConnected() || id == null) return;
        switch (id) {
            case NOISE_MODE:
                changeNoise(value, flushed);
                break;
            case ANC_LEVEL:
                if (!noiseConfirmed || !ancLevelConfirmed || !ANC_LEVEL_SPEC.isValidValue(value)) return;
                if (value == RoseProtocol.WIND_WIRE) {
                    writeItem(RoseProtocol.ITEM_NOISE, (byte) RoseProtocol.WIND_WIRE, flushed);
                } else {
                    if (lastNoiseRaw == RoseProtocol.WIND_WIRE) {
                        write(RoseProtocol.encode(family, sequence++, new byte[]{RoseProtocol.ITEM_NOISE, 1}));
                    }
                    writeItem(RoseProtocol.ITEM_ANC_DEPTH, (byte) value, flushed);
                }
                break;
            case LOW_LATENCY:
                if (lowLatencyConfirmed && (value == 0 || value == 1)) {
                    writeItem(RoseProtocol.ITEM_GAME, (byte) value, flushed);
                }
                break;
            case EQUALIZER:
                if (eqConfirmed && RoseProtocol.isEqPreset(value)) {
                    writeItem(RoseProtocol.ITEM_EQ, (byte) value, flushed);
                }
                break;
            case WEAR_DETECTION:
                if (wearDetectConfirmed && (value == 0 || value == 1)) {
                    writeItem(RoseProtocol.ITEM_IN_EAR, RoseProtocol.inEarToWire(value != 0), flushed);
                }
                break;
            default:
                break;
        }
    }

    private void writeItem(int itemType, byte value, Runnable flushed) {
        writeThen(RoseProtocol.encode(family, sequence++, new byte[]{(byte) itemType, value}), () -> {
            if (flushed != null) flushed.run();
            cancel(confirm);
            schedule(confirm, 700);
        });
    }

    @Override public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0) | (eqConfirmed ? CAP_EQUALIZER : 0);
    }
    @Override public int verifLevel() { return VERIF_UNTESTED; }
    @Override public String brandName() { return "弱水时砂"; }
    @Override public int potentialCaps() {
        return caps() | (name == null || RoseProtocol.supportsNoise(name) ? CAP_NOISE : 0) | CAP_EQUALIZER;
    }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            return RoseProtocol.familyForName(name) >= 0;
        }
        @Override public WireDriver create() { return new RoseLinkClient(); }
    }

}

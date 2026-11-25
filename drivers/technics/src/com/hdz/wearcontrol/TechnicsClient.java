package com.hdz.wearcontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 按合法回包开放电量、三态、降噪/通透强度与自适应降噪；绝不探测未知 Airoha RACE ID。 */
final class TechnicsClient extends WireDriver {
    static final int ADAPTIVE_OPTION_ID = 101;
    private static final long FRAME_INTERVAL_MS = 120L;
    private static final long READY_TIMEOUT_MS = 5000L;

    private static final OptionItem[] LEVEL_OPTIONS = {
            new OptionItem(25, "25%", "25%", OptionItem.ROLE_DEFAULT),
            new OptionItem(50, "50%", "50%", OptionItem.ROLE_DEFAULT),
            new OptionItem(75, "75%", "75%", OptionItem.ROLE_DEFAULT),
            new OptionItem(100, "100%", "100%", OptionItem.ROLE_DEFAULT)
    };
    private static final OptionItem[] ANC_ADAPTIVE_OPTIONS = {
            new OptionItem(25, "25%", "25%", OptionItem.ROLE_DEFAULT),
            new OptionItem(50, "50%", "50%", OptionItem.ROLE_DEFAULT),
            new OptionItem(75, "75%", "75%", OptionItem.ROLE_DEFAULT),
            new OptionItem(100, "100%", "100%", OptionItem.ROLE_DEFAULT),
            new OptionItem(ADAPTIVE_OPTION_ID, "自适应", "Adaptive", OptionItem.ROLE_ADAPTIVE)
    };
    private static final FeatureDescriptor ANC_LEVEL_SPEC_BASE = FeatureDescriptor.choice(
            FeatureId.ANC_LEVEL, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false, LEVEL_OPTIONS);
    private static final FeatureDescriptor ANC_LEVEL_SPEC_ADAPTIVE = FeatureDescriptor.choice(
            FeatureId.ANC_LEVEL, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false, ANC_ADAPTIVE_OPTIONS);
    private static final FeatureDescriptor TRANS_MODE_SPEC = FeatureDescriptor.choice(
            FeatureId.TRANS_MODE, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_TRANS, false, LEVEL_OPTIONS);

    private TechnicsProtocol.Decoder decoder;
    private final int[] battery = {255, 255, 255};
    private volatile boolean noiseConfirmed, adaptiveConfirmed, adaptiveEnabled;
    private volatile FeatureDescriptor ancLevelSpec;
    private int change, seriesToken;
    private int ancLevel = 100, ambientLevel = 50;
    private boolean modeSeriesActive, batterySeriesActive, pendingBattery;
    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            if (!isConnected()) fail("Technics 控制通道未响应");
        }
    };

    @Override protected UUID[] services(UUID[] advertised) {
        return new UUID[]{UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"),
                UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")};
    }
    @Override protected void opened() {
        decoder = new TechnicsProtocol.Decoder();
        java.util.Arrays.fill(battery, 255);
        noiseConfirmed = adaptiveConfirmed = adaptiveEnabled = false;
        ancLevelSpec = null;
        modeSeriesActive = batterySeriesActive = pendingBattery = false;
        change = seriesToken = 0;
        ancLevel = 100;
        ambientLevel = 50;
        schedule(timeout, READY_TIMEOUT_MS);
        queryNoise();
        queryBattery();
    }
    @Override protected void closed() {
        cancel(timeout);
        ++change;
        ++seriesToken;
        modeSeriesActive = batterySeriesActive = pendingBattery = false;
    }
    private void markReady() {
        if (isConnected()) return;
        cancel(timeout);
        ready();
    }
    @Override protected void received(byte[] data) {
        for (byte[] frame : decoder.feed(data)) {
            int mode = TechnicsProtocol.mode(frame);
            if (mode >= 0) {
                ancLevel = TechnicsProtocol.ancLevel(frame);
                ambientLevel = TechnicsProtocol.ambientLevel(frame);
                noiseConfirmed = true;
                markReady();
                events().onNoiseMode(mode);
                FeatureDescriptor spec = adaptiveConfirmed ? ANC_LEVEL_SPEC_ADAPTIVE : ANC_LEVEL_SPEC_BASE;
                ancLevelSpec = spec;
                int reportedAnc = adaptiveEnabled ? ADAPTIVE_OPTION_ID : TechnicsProtocol.snapLevel(ancLevel);
                confirmFeature(spec, reportedAnc);
                confirmFeature(TRANS_MODE_SPEC, TechnicsProtocol.snapLevel(ambientLevel));
            }
            Boolean adaptive = TechnicsProtocol.adaptiveAnc(frame);
            if (adaptive != null) {
                adaptiveConfirmed = true;
                adaptiveEnabled = adaptive.booleanValue();
                ancLevelSpec = ANC_LEVEL_SPEC_ADAPTIVE;
                int reportedAnc = adaptiveEnabled ? ADAPTIVE_OPTION_ID : TechnicsProtocol.snapLevel(ancLevel);
                confirmFeature(ANC_LEVEL_SPEC_ADAPTIVE, reportedAnc);
            }
            int[] value = TechnicsProtocol.battery(frame);
            if (value != null) {
                battery[value[0]] = value[1];
                markReady();
                events().onBattery(battery[0], battery[1], battery[2], -1);
            }
        }
    }
    @Override public void queryBattery() {
        schedule(new Runnable() { @Override public void run() {
            if (!transportOpen()) return;
            if (modeSeriesActive || batterySeriesActive) {
                pendingBattery = true;
                return;
            }
            startBatterySeries();
        }}, 0);
    }
    private void startBatterySeries() {
        if (!transportOpen()) return;
        pendingBattery = false;
        batterySeriesActive = true;
        writeSeries(new byte[][]{
                TechnicsProtocol.command(0x0CD6, (byte) 0),
                TechnicsProtocol.command(0x0CD6, (byte) 1),
                TechnicsProtocol.command(0x0040)
        }, 0, ++seriesToken, 0, null, false);
    }
    @Override public void queryNoise() {
        schedule(new Runnable() { @Override public void run() { write(TechnicsProtocol.command(0x000A)); }}, 0);
    }
    private void queryAdaptive() {
        schedule(new Runnable() { @Override public void run() {
            if (transportOpen()) write(TechnicsProtocol.adaptiveQuery());
        }}, 0);
    }
    @Override public void changeNoise(final int mode, final Runnable flushed) {
        schedule(new Runnable() { @Override public void run() {
            final byte[][] commands = TechnicsProtocol.noiseCommands(mode, ancLevel, ambientLevel);
            if (!transportOpen() || !noiseConfirmed || commands == null) return;
            adaptiveEnabled = false;
            startModeSeries(commands, flushed, false);
        }}, 0);
    }

    private void startModeSeries(byte[][] commands, Runnable flushed, boolean readAdaptive) {
        if (batterySeriesActive) {
            // 让位给降噪切换序列，避免与三帧切档交错。
            batterySeriesActive = false;
            pendingBattery = true;
        }
        modeSeriesActive = true;
        writeSeries(commands, 0, ++seriesToken, ++change, flushed, readAdaptive);
    }

    @Override public FeatureDescriptor[] featureDescriptors() {
        List<FeatureDescriptor> list = new ArrayList<>();
        if (ancLevelSpec != null) list.add(ancLevelSpec);
        if (noiseConfirmed) list.add(TRANS_MODE_SPEC);
        return list.toArray(new FeatureDescriptor[0]);
    }

    @Override public void queryFeature(final FeatureId id) {
        if (id == null) return;
        if (id == FeatureId.NOISE_MODE || id == FeatureId.TRANS_MODE) {
            queryNoise();
        } else if (id == FeatureId.ANC_LEVEL) {
            queryNoise();
            queryAdaptive();
        }
    }

    @Override public void writeFeature(final FeatureId id, final int value, final Runnable flushed) {
        if (id == null) return;
        schedule(new Runnable() { @Override public void run() {
            if (!transportOpen() || !noiseConfirmed) return;
            if (id == FeatureId.NOISE_MODE) {
                changeNoise(value, flushed);
                return;
            }
            if (id == FeatureId.ANC_LEVEL) {
                FeatureDescriptor spec = ancLevelSpec;
                if (spec == null || !spec.isValidValue(value)) return;
                if (value == ADAPTIVE_OPTION_ID) {
                    if (!adaptiveConfirmed) return;
                    adaptiveEnabled = true;
                    startModeSeries(TechnicsProtocol.adaptiveAncCommands(true, ancLevel, ambientLevel), flushed, true);
                } else {
                    ancLevel = value;
                    adaptiveEnabled = false;
                    startModeSeries(TechnicsProtocol.noiseCommands(MODE_ANC, ancLevel, ambientLevel),
                            flushed, adaptiveConfirmed);
                }
            } else if (id == FeatureId.TRANS_MODE) {
                if (!TRANS_MODE_SPEC.isValidValue(value)) return;
                ambientLevel = value;
                adaptiveEnabled = false;
                startModeSeries(TechnicsProtocol.noiseCommands(MODE_TRANS, ancLevel, ambientLevel), flushed, false);
            }
        }}, 0);
    }

    /** Tokens keep an older mode or battery series from continuing or interleaving after a newer tap. */
    private void writeSeries(final byte[][] commands, final int index, final int token,
                             final int changeId, final Runnable flushed, final boolean readAdaptive) {
        if (!transportOpen() || token != seriesToken || (changeId != 0 && changeId != change)) return;
        writeThen(commands[index], new Runnable() { @Override public void run() {
            if (!transportOpen() || token != seriesToken || (changeId != 0 && changeId != change)) return;
            if (index + 1 < commands.length) {
                schedule(new Runnable() { @Override public void run() {
                    writeSeries(commands, index + 1, token, changeId, flushed, readAdaptive);
                }}, FRAME_INTERVAL_MS);
            } else {
                if (changeId != 0) {
                    modeSeriesActive = false;
                    if (flushed != null) flushed.run();
                    schedule(new Runnable() { @Override public void run() {
                        if (changeId == change) {
                            queryNoise();
                            if (readAdaptive) queryAdaptive();
                        }
                    }}, FRAME_INTERVAL_MS + 400L);
                } else {
                    batterySeriesActive = false;
                }
                if (pendingBattery && !modeSeriesActive && !batterySeriesActive) {
                    schedule(new Runnable() { @Override public void run() {
                        if (pendingBattery && !modeSeriesActive && !batterySeriesActive) startBatterySeries();
                    }}, FRAME_INTERVAL_MS);
                }
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

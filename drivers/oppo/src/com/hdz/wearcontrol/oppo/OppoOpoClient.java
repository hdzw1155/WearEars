package com.hdz.wearcontrol.oppo;


import com.hdz.wearcontrol.FeatureDescriptor;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.OptionItem;
import com.hdz.wearcontrol.SoundPreset;
import com.hdz.wearcontrol.WireDriver;
import com.hdz.wearcontrol.Controls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OPPO Enco / realme / OnePlus 耳机(OPOv1 / HeyMelody 协议)客户端, 经典蓝牙 RFCOMM。
 *
 * 帧: AA | TotalLen(LEB128) | slice(1) | 00 | cmd(2B LE) | seq(1) | payLen(2B LE) | payload
 *   TotalLen = 7 + payLen(整帧); 无校验。
 *
 * 握手: 连上后 300ms 发 0x0100(空), 之后每 200ms 依次
 *   0x0103 产品ID → 0x0200 → 0x0205 订阅 → 0x010D → 0x010C 查降噪 →
 *   0x0106 查电量 → 0x0109 佩戴 → 0x0108 → 0x0105。
 *   0x8100 应答里带命令位图; 0x8103 应答里带产品 ID(Air5 Pro = 06 C4 10)。
 *
 * 降噪: 0x0404 写, payload = 01 01 <mask>: 关=0x01 / 降噪=0x02 / 通透=0x04。
 *   读 0x010C, payload = 01 01; 应答/推送里的状态位与 SET 不同:
 *   关=0x0008 / 降噪=0x0002 / 通透=0x0100。
 *   耳机主动推送 0x0204, subtype 0x01=电量, 0x02=佩戴, 0x03=降噪(事件 payload 03 01 01 <mask LE>)。
 * 电量: 0x0106 查询, 应答 0x8106 = [00][count](part, raw)..., part 1左/2右/3盒;
 *   raw & 0x7F 电量(0 未知), raw & 0x80 充电。
 */
public class OppoOpoClient extends WireDriver {

    private static final UUID UUID_OPPO =
            UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5");
    private static final UUID UUID_FALLBACK =
            UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5");
    private static final int RFCOMM_CHANNEL = 15;

    private static final int CMD_HELLO = OppoProtocol.CMD_HELLO;
    private static final int CMD_PRODUCT = OppoProtocol.CMD_PRODUCT;
    private static final int CMD_BATTERY_QUERY = OppoProtocol.CMD_BATTERY_QUERY;
    private static final int CMD_ANC_QUERY = OppoProtocol.CMD_ANC_QUERY;
    private static final int CMD_WEAR_QUERY = OppoProtocol.CMD_WEAR_QUERY;
    private static final int CMD_ANC_SET = OppoProtocol.CMD_ANC_SET;
    private static final int CMD_CAPS = OppoProtocol.CMD_CAPS;
    private static final int CMD_SUBSCRIBE = OppoProtocol.CMD_SUBSCRIBE;
    private static final int CMD_010D = OppoProtocol.CMD_FEATURE_QUERY;
    private static final int CMD_0108 = OppoProtocol.CMD_0108;
    private static final int CMD_0105 = OppoProtocol.CMD_FIRMWARE;

    private static final int RSP_BATTERY = OppoProtocol.RSP_BATTERY;
    private static final int RSP_ANC = OppoProtocol.RSP_ANC;
    private static final int RSP_ANC_ACK = OppoProtocol.RSP_ANC_ACK;
    private static final int RSP_HELLO = OppoProtocol.RSP_HELLO;
    private static final int RSP_PRODUCT = OppoProtocol.RSP_PRODUCT;
    private static final int RSP_WEAR = OppoProtocol.RSP_WEAR;
    private static final int CMD_EVENT = OppoProtocol.CMD_EVENT;

    private static final int MASK_ANC_SET_OFF = 0x01;
    private static final int MASK_ANC_SET_ANC = 0x02;
    private static final int MASK_ANC_SET_TRANS = 0x04;

    private OppoProtocol.Decoder decoder;
    private volatile boolean protocolReady;
    private volatile boolean noiseConfirmed;
    private volatile boolean eqConfirmed;
    private volatile boolean spatialConfirmed;
    private boolean anyResponse;
    private volatile String connectedName = "";
    private int seq = 1;
    private int initialMode = -1;
    private int initialAncMask = -1;
    private int[] initialBattery;

    private byte[] capBitmap;
    private int productId = -1;
    private String firmware = "";
    private boolean seen010D;
    private final Map<Integer, Integer> features010D = new HashMap<Integer, Integer>();
    private int initialWear = -1;
    private Boolean leftInEar;
    private Boolean rightInEar;
    private Integer initialEq;
    private Integer initialSpatial;
    private SoundPreset[] eqPresets = new SoundPreset[0];
    private boolean spatialMode47;
    private boolean spatialMode43;
    private int pendingFindTarget;

    private FeatureDescriptor ancLevelSpec;
    private FeatureDescriptor transModeSpec;
    private FeatureDescriptor windSpec;
    private FeatureDescriptor lowLatencySpec;
    private FeatureDescriptor multipointSpec;
    private FeatureDescriptor wearDetectSpec;
    private FeatureDescriptor wearStatusSpec;
    private FeatureDescriptor eqSpec;
    private FeatureDescriptor spatialSpec;
    private FeatureDescriptor findBudsSpec;
    private FeatureDescriptor firmwareSpec;

    @Override protected String logTag() { return "OppoClient"; }
    @Override protected String unnamedDevice() { return null; }
    @Override protected boolean cancelDiscovery() { return true; }
    @Override protected String connectionFailure() { return "找不到 OPPO 控制服务(信道 15)，耳机需先与手表配对"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{UUID_OPPO, UUID_FALLBACK}; }
    @Override protected int[] channels() { return new int[]{RFCOMM_CHANNEL}; }
    @Override protected void prepare() {
        protocolReady = noiseConfirmed = eqConfirmed = spatialConfirmed = anyResponse = false;
        connectedName = deviceName();
        seq = 1;
        initialMode = -1;
        initialAncMask = -1;
        initialBattery = null;
        capBitmap = null;
        productId = -1;
        firmware = "";
        seen010D = false;
        features010D.clear();
        initialWear = -1;
        leftInEar = null;
        rightInEar = null;
        initialEq = null;
        initialSpatial = null;
        eqPresets = new SoundPreset[0];
        spatialMode47 = false;
        spatialMode43 = false;
        pendingFindTarget = 0;
        ancLevelSpec = transModeSpec = windSpec = lowLatencySpec = multipointSpec = null;
        wearDetectSpec = wearStatusSpec = eqSpec = spatialSpec = findBudsSpec = firmwareSpec = null;
        decoder = new OppoProtocol.Decoder();
    }
    @Override protected void opened() {
        initStep(0, 300);
    }
    @Override protected void closed() { protocolReady = false; }

    private static final int[] INIT_COMMANDS = {CMD_HELLO, CMD_PRODUCT, CMD_CAPS, CMD_SUBSCRIBE, CMD_010D,
            CMD_ANC_QUERY, CMD_BATTERY_QUERY, CMD_WEAR_QUERY, CMD_0108, CMD_0105};

    private void initStep(final int index, long delay) {
        schedule(new Runnable() { @Override public void run() {
            if (index == INIT_COMMANDS.length) {
                markReady();
                return;
            }
            int command = INIT_COMMANDS[index];
            byte[] payload = command == CMD_SUBSCRIBE ? new byte[]{0x03, 0x01, 0x02, 0x03}
                    : command == CMD_ANC_QUERY ? new byte[]{0x01, 0x01} : null;
            writeThen(frame(command, nextSeq(), payload), () ->
                    initStep(index + 1, index + 1 == INIT_COMMANDS.length ? 400 : 200));
        }}, delay);
    }

    @Override
    public String brandName() {
        return "OPPO";
    }

    @Override
    public int caps() {
        return CAP_BATTERY
                | (noiseConfirmed ? CAP_NOISE : 0)
                | (eqConfirmed ? CAP_EQUALIZER : 0)
                | (spatialConfirmed ? CAP_SPATIAL : 0);
    }

    @Override
    public int verifLevel() {
        return OppoProtocol.isVerifiedReal(connectedName) ? VERIF_REAL : VERIF_UNTESTED;
    }

    @Override
    public SoundPreset[] equalizerPresets() {
        return eqPresets.clone();
    }

    @Override
    public FeatureDescriptor[] featureDescriptors() {
        List<FeatureDescriptor> list = new ArrayList<FeatureDescriptor>();
        if (ancLevelSpec != null) list.add(ancLevelSpec);
        if (transModeSpec != null) list.add(transModeSpec);
        if (windSpec != null) list.add(windSpec);
        if (lowLatencySpec != null) list.add(lowLatencySpec);
        if (multipointSpec != null) list.add(multipointSpec);
        if (wearDetectSpec != null) list.add(wearDetectSpec);
        if (wearStatusSpec != null) list.add(wearStatusSpec);
        if (eqSpec != null) list.add(eqSpec);
        if (spatialSpec != null) list.add(spatialSpec);
        if (findBudsSpec != null) list.add(findBudsSpec);
        if (firmwareSpec != null) list.add(firmwareSpec);
        return list.toArray(new FeatureDescriptor[0]);
    }

    private synchronized int nextSeq() {
        int s = seq & 0xFF;
        if (s == 0 || s == 0xFF) s = 1;
        seq = s + 1;
        if (seq >= 0xFF) seq = 1;
        return s;
    }

    private void markReady() {
        if (protocolReady || !transportOpen()) return;
        if (!anyResponse) {
            fail(connectionFailure());
            return;
        }
        refreshSpecs(false);
        protocolReady = true;
        ready();
        trace("OppoClient", "init done, ready");
        emitInitialFeatures();
        if (initialMode >= 0) {
            noiseConfirmed = true;
            events().onNoiseMode(initialMode);
        }
        if (initialBattery != null) events().onBattery(initialBattery[0], initialBattery[1], initialBattery[2], initialBattery[3]);
        queryBattery();
        queryNoise();
    }

    private void emitInitialFeatures() {
        if (ancLevelSpec != null) {
            int matchedAnc = matchOptionMask(ancLevelSpec, initialAncMask);
            if (matchedAnc >= 0) {
                confirmFeature(ancLevelSpec, matchedAnc);
            } else {
                confirmFeature(ancLevelSpec);
            }
        }
        if (transModeSpec != null) {
            int matchedTrans = matchOptionMask(transModeSpec, initialAncMask);
            if (matchedTrans >= 0) {
                confirmFeature(transModeSpec, matchedTrans);
            } else {
                confirmFeature(transModeSpec);
            }
        }
        emitSwitchFeatureOnReady(windSpec, OppoProtocol.FEAT_WIND_REDUCTION);
        emitSwitchFeatureOnReady(lowLatencySpec, OppoProtocol.FEAT_LOW_LATENCY);
        emitSwitchFeatureOnReady(multipointSpec, OppoProtocol.FEAT_MULTIPOINT);
        emitSwitchFeatureOnReady(wearDetectSpec, OppoProtocol.FEAT_WEAR_DETECTION);
        if (wearStatusSpec != null) {
            confirmFeature(wearStatusSpec, initialWear);
        }
        if (eqSpec != null) {
            confirmFeature(eqSpec);
            if (initialEq != null && eqSpec.findOption(initialEq.intValue()) != null) {
                eqConfirmed = true;
                events().onEqualizer(initialEq);
            }
        }
        if (spatialSpec != null) {
            Integer spatialVal = spatialMode47 ? initialSpatial
                    : (spatialMode43 ? features010D.get(OppoProtocol.FEAT_SPATIAL_AUDIO) : null);
            if (spatialVal != null && spatialSpec.isValidValue(spatialVal.intValue())) {
                spatialConfirmed = true;
                confirmFeature(spatialSpec, spatialVal.intValue());
            } else {
                confirmFeature(spatialSpec);
            }
        }
        if (findBudsSpec != null) {
            confirmFeature(findBudsSpec);
        }
        if (firmwareSpec != null) {
            confirmFeature(firmwareSpec);
            reportFeatureText(FeatureId.FIRMWARE_INFO, firmware);
        }
    }

    private void emitSwitchFeatureOnReady(FeatureDescriptor spec, int featId) {
        if (spec == null) return;
        Integer val = features010D.get(featId);
        if (val != null) confirmFeature(spec, val.intValue());
        else confirmFeature(spec);
    }

    private void refreshSpecs(boolean emitIfNew) {
        boolean allowAncGroup = capBitmap == null || OppoProtocol.hasCapGroup(capBitmap, OppoProtocol.CAP_GRP_ANC);

        FeatureDescriptor nextAnc = null;
        if (allowAncGroup && productId >= 0 && OppoProtocol.supportsUnifiedAnc(productId)) {
            OptionItem[] opts = OppoProtocol.ancLevelOptions(productId);
            if (opts.length > 0) {
                nextAnc = FeatureDescriptor.choice(FeatureId.ANC_LEVEL, verifLevel(),
                        FeatureDescriptor.SAFETY_NONE, 1 << MODE_ANC, false, opts,
                        "仅降噪模式", "ANC only");
            }
        }
        if (nextAnc != null && ancLevelSpec == null) {
            ancLevelSpec = nextAnc;
            if (emitIfNew) confirmFeature(ancLevelSpec);
        }

        FeatureDescriptor nextTrans = null;
        if (allowAncGroup && productId >= 0) {
            OptionItem[] opts = OppoProtocol.transModeOptions(productId);
            if (opts.length > 0) {
                nextTrans = FeatureDescriptor.choice(FeatureId.TRANS_MODE, VERIF_UNTESTED,
                        FeatureDescriptor.SAFETY_NONE, 1 << MODE_TRANS, false, opts,
                        "仅通透模式", "Transparency only");
            }
        }
        if (nextTrans != null && transModeSpec == null) {
            transModeSpec = nextTrans;
            if (emitIfNew) confirmFeature(transModeSpec);
        }

        if (features010D.containsKey(OppoProtocol.FEAT_WIND_REDUCTION) && windSpec == null) {
            windSpec = FeatureDescriptor.toggle(FeatureId.WIND_REDUCTION, VERIF_UNTESTED, false);
            if (emitIfNew) confirmFeature(windSpec);
        }

        boolean hasLowLat = features010D.containsKey(OppoProtocol.FEAT_LOW_LATENCY)
                || (!seen010D && OppoProtocol.hasCapGroup(capBitmap, OppoProtocol.CAP_GRP_FEATURE_SWITCH));
        if (hasLowLat) {
            if (lowLatencySpec == null) {
                lowLatencySpec = FeatureDescriptor.toggle(FeatureId.LOW_LATENCY, VERIF_UNTESTED, false);
                if (emitIfNew) confirmFeature(lowLatencySpec);
            }
        } else if (!emitIfNew) {
            lowLatencySpec = null;
        }

        if (features010D.containsKey(OppoProtocol.FEAT_MULTIPOINT) && multipointSpec == null) {
            multipointSpec = FeatureDescriptor.toggle(FeatureId.MULTIPOINT, VERIF_UNTESTED, false);
            if (emitIfNew) confirmFeature(multipointSpec);
        }

        if (features010D.containsKey(OppoProtocol.FEAT_WEAR_DETECTION) && wearDetectSpec == null) {
            wearDetectSpec = FeatureDescriptor.toggle(FeatureId.WEAR_DETECTION, VERIF_UNTESTED, false);
            if (emitIfNew) confirmFeature(wearDetectSpec);
        }

        if (initialWear >= 0 && wearStatusSpec == null) {
            wearStatusSpec = FeatureDescriptor.readOnly(FeatureId.WEAR_STATUS, VERIF_UNTESTED, false);
            if (emitIfNew) confirmFeature(wearStatusSpec);
        }

        if (productId >= 0 && OppoProtocol.hasCapGroup(capBitmap, OppoProtocol.CAP_GRP_EQ) && eqSpec == null) {
            SoundPreset[] presets = OppoProtocol.equalizerPresets(productId);
            if (presets.length > 0) {
                eqPresets = presets;
                eqSpec = FeatureDescriptor.choice(FeatureId.EQUALIZER, VERIF_UNTESTED,
                        FeatureDescriptor.SAFETY_NONE, 0, false,
                        OptionItem.fromPresets(presets), "", "");
                if (emitIfNew) confirmFeature(eqSpec);
            }
        }

        boolean grp47 = OppoProtocol.hasCapGroup(capBitmap, OppoProtocol.CAP_GRP_SPATIAL_47);
        boolean grp43 = OppoProtocol.hasCapGroup(capBitmap, OppoProtocol.CAP_GRP_SPATIAL_43);
        if (grp47 && !spatialMode47) {
            spatialMode47 = true;
            spatialMode43 = false;
            spatialSpec = FeatureDescriptor.choice(FeatureId.SPATIAL_AUDIO, VERIF_UNTESTED,
                    FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true,
                    new OptionItem[]{
                            new OptionItem(0, "关闭", "Off"),
                            new OptionItem(1, "固定", "Fixed"),
                            new OptionItem(2, "头部追踪", "Head Tracking")
                    }, "", "");
            if (emitIfNew) confirmFeature(spatialSpec);
        } else if (!grp47 && grp43
                && (features010D.containsKey(OppoProtocol.FEAT_SPATIAL_AUDIO) || !seen010D)) {
            if (spatialSpec == null) {
                spatialMode43 = true;
                spatialSpec = FeatureDescriptor.toggle(FeatureId.SPATIAL_AUDIO, VERIF_UNTESTED,
                        FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true);
                if (emitIfNew) confirmFeature(spatialSpec);
            }
        } else if (!grp47 && !emitIfNew) {
            spatialMode43 = false;
            spatialSpec = null;
        }

        if (OppoProtocol.hasCapGroup(capBitmap, OppoProtocol.CAP_GRP_FIND_BUDS) && findBudsSpec == null) {
            findBudsSpec = FeatureDescriptor.findBuds(
                    VERIF_UNTESTED,
                    false,
                    new OptionItem[]{new OptionItem(3, "双耳响铃", "Ring Both")},
                    "查找耳机仅支持双耳响铃；入耳检测触发时禁止响铃",
                    "Find My Buds supports ringing both buds only; blocked while worn");
            if (emitIfNew) confirmFeature(findBudsSpec);
        }

        if (!firmware.isEmpty() && firmwareSpec == null) {
            firmwareSpec = FeatureDescriptor.readOnly(FeatureId.FIRMWARE_INFO, VERIF_UNTESTED, false);
            if (emitIfNew) confirmFeature(firmwareSpec);
        }
    }

    // ---- 命令 ----

    @Override
    public void queryBattery() {
        if (!protocolReady) return;
        writeFrame(CMD_BATTERY_QUERY, 0xF0, null);
    }

    @Override
    public void queryNoise() {
        if (!protocolReady) return;
        writeFrame(CMD_ANC_QUERY, 0xF1, new byte[]{0x01, 0x01});
    }

    @Override
    public synchronized void changeNoise(int mode, Runnable flushed) {
        if (mode != MODE_ANC && mode != MODE_OFF && mode != MODE_TRANS) return;
        if (!protocolReady || !noiseConfirmed) return;
        int mask;
        if (mode == MODE_ANC) mask = MASK_ANC_SET_ANC;
        else if (mode == MODE_TRANS) mask = MASK_ANC_SET_TRANS;
        else mask = MASK_ANC_SET_OFF;
        writeThen(frame(CMD_ANC_SET, 0xF3, new byte[]{0x01, 0x01, (byte) mask}), () -> {
            if (flushed != null) flushed.run();
            schedule(this::queryNoise, 350);
        });
    }

    @Override
    public void querySpatial() {
        queryFeature(FeatureId.SPATIAL_AUDIO);
    }

    @Override
    public void setSpatial(boolean enabled) {
        writeFeature(FeatureId.SPATIAL_AUDIO, enabled ? 1 : 0, null);
    }

    @Override
    public void queryEqualizer() {
        if (!protocolReady || eqSpec == null) return;
        writeFrame(OppoProtocol.CMD_EQ_QUERY, nextSeq(), null);
    }

    @Override
    public void setEqualizer(int preset) {
        writeFeature(FeatureId.EQUALIZER, preset, null);
    }

    @Override
    public void queryFeature(FeatureId id) {
        if (!protocolReady || id == null) return;
        switch (id) {
            case NOISE_MODE:
                queryNoise();
                break;
            case ANC_LEVEL:
                if (ancLevelSpec != null) writeFrame(CMD_ANC_QUERY, nextSeq(), new byte[]{0x01, 0x01});
                break;
            case TRANS_MODE:
                if (transModeSpec != null) writeFrame(CMD_ANC_QUERY, nextSeq(), new byte[]{0x01, 0x01});
                break;
            case WIND_REDUCTION:
                if (windSpec != null) writeFrame(CMD_010D, nextSeq(), null);
                break;
            case LOW_LATENCY:
                if (lowLatencySpec != null) writeFrame(CMD_010D, nextSeq(), null);
                break;
            case MULTIPOINT:
                if (multipointSpec != null) writeFrame(CMD_010D, nextSeq(), null);
                break;
            case WEAR_DETECTION:
                if (wearDetectSpec != null) writeFrame(CMD_010D, nextSeq(), null);
                break;
            case WEAR_STATUS:
                writeFrame(CMD_WEAR_QUERY, nextSeq(), null);
                break;
            case EQUALIZER:
                queryEqualizer();
                break;
            case SPATIAL_AUDIO:
                if (spatialMode47) writeFrame(OppoProtocol.CMD_SPATIAL_QUERY, nextSeq(), null);
                else if (spatialMode43) writeFrame(CMD_010D, nextSeq(), null);
                break;
            case FIRMWARE_INFO:
                writeFrame(CMD_0105, nextSeq(), null);
                break;
            case FIND_MY_BUDS:
            default:
                break;
        }
    }

    @Override
    public synchronized void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (!protocolReady || id == null) return;
        switch (id) {
            case NOISE_MODE:
                changeNoise(value, flushed != null ? flushed : () -> { });
                break;
            case ANC_LEVEL:
                if (ancLevelSpec == null || !ancLevelSpec.isValidValue(value)) return;
                writeAncSubMode(value, flushed);
                break;
            case TRANS_MODE:
                if (transModeSpec == null || !transModeSpec.isValidValue(value)) return;
                writeAncSubMode(value, flushed);
                break;
            case WIND_REDUCTION:
                if (windSpec == null || !features010D.containsKey(OppoProtocol.FEAT_WIND_REDUCTION)
                        || !windSpec.isValidValue(value)) return;
                writeThen(frame(OppoProtocol.CMD_FEATURE_SET, nextSeq(),
                        new byte[]{(byte) OppoProtocol.FEAT_WIND_REDUCTION, (byte) value}), flushed);
                break;
            case LOW_LATENCY:
                if (lowLatencySpec == null || !lowLatencySpec.isValidValue(value)) return;
                writeThen(frame(OppoProtocol.CMD_FEATURE_SET, nextSeq(),
                        new byte[]{(byte) OppoProtocol.FEAT_LOW_LATENCY, (byte) value}), flushed);
                break;
            case MULTIPOINT:
                if (multipointSpec == null || !multipointSpec.isValidValue(value)) return;
                writeThen(frame(OppoProtocol.CMD_FEATURE_SET, nextSeq(),
                        new byte[]{(byte) OppoProtocol.FEAT_MULTIPOINT, (byte) value}), flushed);
                break;
            case WEAR_DETECTION:
                if (wearDetectSpec == null || !wearDetectSpec.isValidValue(value)) return;
                writeThen(frame(OppoProtocol.CMD_FEATURE_SET, nextSeq(),
                        new byte[]{(byte) OppoProtocol.FEAT_WEAR_DETECTION, (byte) value}), flushed);
                break;
            case EQUALIZER:
                if (eqSpec == null || !eqSpec.isValidValue(value)) return;
                writeThen(frame(OppoProtocol.CMD_EQ_SET, nextSeq(), new byte[]{(byte) value}), flushed);
                break;
            case SPATIAL_AUDIO:
                if (spatialSpec == null || !spatialSpec.isValidValue(value)) return;
                if (spatialMode47) {
                    writeThen(frame(OppoProtocol.CMD_SPATIAL_47_SET, nextSeq(), new byte[]{(byte) value}), flushed);
                } else if (spatialMode43) {
                    byte v = (byte) (value != 0 ? 1 : 0);
                    if (features010D.containsKey(OppoProtocol.FEAT_SPATIAL_AUDIO)
                            || OppoProtocol.hasCapGroup(capBitmap, OppoProtocol.CAP_GRP_FEATURE_SWITCH)) {
                        write(frame(OppoProtocol.CMD_FEATURE_SET, nextSeq(),
                                new byte[]{(byte) OppoProtocol.FEAT_SPATIAL_AUDIO, v}));
                    }
                    writeThen(frame(OppoProtocol.CMD_SPATIAL_43_SET, nextSeq(), new byte[]{v}), flushed);
                }
                break;
            case FIND_MY_BUDS:
                if (findBudsSpec == null || !findBudsSpec.isValidValue(value)) return;
                pendingFindTarget = value;
                writeThen(frame(OppoProtocol.CMD_FIND_BUDS, nextSeq(),
                        new byte[]{(byte) (value != 0 ? 1 : 0)}), flushed);
                break;
            default:
                break;
        }
    }

    private void writeAncSubMode(int mask, Runnable flushed) {
        writeThen(frame(CMD_ANC_SET, nextSeq(), OppoProtocol.ancSetPayload(mask)), () -> {
            if (flushed != null) flushed.run();
            schedule(() -> {
                if (protocolReady) writeFrame(CMD_ANC_QUERY, nextSeq(), new byte[]{0x01, 0x01});
            }, 350);
        });
    }

    /** 组帧: AA TotalLen(LEB128) 00 00 cmd(2B LE) seq payLen(2B LE) payload */
    private void writeFrame(int cmd, int seqNo, byte[] payload) { write(frame(cmd, seqNo, payload)); }

    private static byte[] frame(int cmd, int seqNo, byte[] payload) {
        return OppoProtocol.encode(cmd, seqNo, payload);
    }

    @Override protected void received(byte[] data) {
        if (!transportOpen() || data == null || data.length == 0) return;
        for (OppoProtocol.Frame frame : decoder.feed(data, data.length)) {
            anyResponse = true;
            dispatch(frame);
        }
    }

    private void dispatch(OppoProtocol.Frame frame) {
        int cmd = frame.command;
        int seqNo = frame.sequence;
        byte[] payload = frame.payload;
        trace("OppoClient", "rx cmd=0x" + Integer.toHexString(cmd)
                + " seq=" + seqNo + " " + toHex(payload));

        if (cmd == RSP_BATTERY) {
            parseBattery(payload, false);
        } else if (cmd == RSP_ANC) {
            parseAncState(payload);
        } else if (cmd == CMD_EVENT) {
            parseEvent(payload);
        } else if (cmd == RSP_ANC_ACK) {
            parseAncAck(payload);
        } else if (cmd == RSP_HELLO) {
            parseHelloCaps(payload);
        } else if (cmd == RSP_PRODUCT) {
            parseProduct(payload);
        } else if (cmd == OppoProtocol.RSP_FIRMWARE) {
            parseFirmware(payload);
        } else if (cmd == RSP_WEAR) {
            parseWear(payload, false);
        } else if (cmd == OppoProtocol.RSP_FEATURE) {
            parseFeatures(payload);
        } else if (cmd == OppoProtocol.RSP_EQ) {
            parseEq(payload, false);
        } else if (cmd == OppoProtocol.CMD_EQ_NOTIFY) {
            parseEq(payload, true);
        } else if (cmd == OppoProtocol.RSP_SPATIAL) {
            parseSpatial47(payload, false);
        } else if (cmd == OppoProtocol.CMD_SPATIAL_NOTIFY) {
            parseSpatial47(payload, true);
        } else if (cmd == OppoProtocol.CMD_FEATURE_NOTIFY) {
            parseFeatureNotify(payload);
        } else if (cmd == OppoProtocol.RSP_FIND_BUDS_ACK) {
            parseFindBudsAck(payload);
        }
    }

    private void parseHelloCaps(byte[] payload) {
        byte[] bitmap = OppoProtocol.capBitmap(payload);
        if (bitmap == null) return;
        capBitmap = bitmap;
        trace("OppoClient", "hello caps " + toHex(payload));
        refreshSpecs(protocolReady);
    }

    private void parseProduct(byte[] payload) {
        int pid = OppoProtocol.productId(payload);
        if (pid < 0) return;
        productId = pid;
        trace("OppoClient", "product id 0x" + Integer.toHexString(pid));
        refreshSpecs(protocolReady);
    }

    private void parseFirmware(byte[] payload) {
        String fw = OppoProtocol.firmware(payload);
        if (fw.isEmpty()) return;
        firmware = fw;
        refreshSpecs(protocolReady);
        if (protocolReady) reportFeatureText(FeatureId.FIRMWARE_INFO, fw);
    }

    private void parseFeatures(byte[] payload) {
        int[][] switches = OppoProtocol.featureSwitches(payload);
        if (switches == null) return;
        seen010D = true;
        features010D.clear();
        for (int[] pair : switches) {
            features010D.put(pair[0], pair[1]);
        }
        if (!features010D.containsKey(OppoProtocol.FEAT_WIND_REDUCTION)) windSpec = null;
        if (spatialMode43 && !features010D.containsKey(OppoProtocol.FEAT_SPATIAL_AUDIO)) {
            spatialMode43 = false;
            spatialSpec = null;
        }
        refreshSpecs(protocolReady);
        if (protocolReady) {
            reportSwitchIfPresent(OppoProtocol.FEAT_WIND_REDUCTION, FeatureId.WIND_REDUCTION, windSpec);
            reportSwitchIfPresent(OppoProtocol.FEAT_LOW_LATENCY, FeatureId.LOW_LATENCY, lowLatencySpec);
            reportSwitchIfPresent(OppoProtocol.FEAT_MULTIPOINT, FeatureId.MULTIPOINT, multipointSpec);
            reportSwitchIfPresent(OppoProtocol.FEAT_WEAR_DETECTION, FeatureId.WEAR_DETECTION, wearDetectSpec);
            if (spatialMode43 && spatialSpec != null
                    && features010D.containsKey(OppoProtocol.FEAT_SPATIAL_AUDIO)) {
                spatialConfirmed = true;
                reportFeature(FeatureId.SPATIAL_AUDIO,
                        features010D.get(OppoProtocol.FEAT_SPATIAL_AUDIO).intValue());
            }
        }
    }

    private void reportSwitchIfPresent(int featId, FeatureId id, FeatureDescriptor spec) {
        if (spec == null) return;
        Integer val = features010D.get(featId);
        if (val != null) reportFeature(id, val.intValue());
    }

    private void parseWear(byte[] payload, boolean event) {
        int[] flags = OppoProtocol.wearFlags(payload, event);
        if (flags == null) return;
        if (flags[0] >= 0) leftInEar = flags[0] == 1;
        if (flags[1] >= 0) rightInEar = flags[1] == 1;
        int mask = (Boolean.TRUE.equals(leftInEar) ? 1 : 0) | (Boolean.TRUE.equals(rightInEar) ? 2 : 0);
        int wear = (mask == 0 && (leftInEar == null || rightInEar == null)) ? -1 : mask;
        initialWear = wear;
        refreshSpecs(protocolReady);
        if (protocolReady) reportFeature(FeatureId.WEAR_STATUS, wear);
    }

    private void parseEq(byte[] payload, boolean notify) {
        int id = OppoProtocol.singleStatusValue(payload, notify);
        if (id < 0) return;
        if (eqSpec != null && eqSpec.findOption(id) == null) return;
        if (protocolReady) {
            if (eqSpec != null) {
                eqConfirmed = true;
                events().onEqualizer(id);
            }
        } else {
            initialEq = id;
        }
    }

    private void parseSpatial47(byte[] payload, boolean notify) {
        int mode = OppoProtocol.singleStatusValue(payload, notify);
        if (mode < 0 || mode > 2) return;
        if (protocolReady) {
            if (spatialMode47 && spatialSpec != null) {
                spatialConfirmed = true;
                reportFeature(FeatureId.SPATIAL_AUDIO, mode);
            }
        } else {
            initialSpatial = mode;
        }
    }

    private void parseFeatureNotify(byte[] payload) {
        int[] pair = OppoProtocol.featureNotify(payload);
        if (pair == null) return;
        int featId = pair[0];
        int val = pair[1];
        if (!seen010D && !features010D.containsKey(featId)
                && (featId == OppoProtocol.FEAT_LOW_LATENCY || featId == OppoProtocol.FEAT_MULTIPOINT
                || featId == OppoProtocol.FEAT_WEAR_DETECTION || featId == OppoProtocol.FEAT_SPATIAL_AUDIO)) {
            features010D.put(featId, val);
            refreshSpecs(protocolReady);
        } else if (features010D.containsKey(featId)) {
            features010D.put(featId, val);
        }
        if (!protocolReady) return;
        if (featId == OppoProtocol.FEAT_LOW_LATENCY && lowLatencySpec != null) {
            reportFeature(FeatureId.LOW_LATENCY, val);
        } else if (featId == OppoProtocol.FEAT_MULTIPOINT && multipointSpec != null) {
            reportFeature(FeatureId.MULTIPOINT, val);
        } else if (featId == OppoProtocol.FEAT_WEAR_DETECTION && wearDetectSpec != null) {
            reportFeature(FeatureId.WEAR_DETECTION, val);
        } else if (featId == OppoProtocol.FEAT_WIND_REDUCTION
                && windSpec != null && features010D.containsKey(OppoProtocol.FEAT_WIND_REDUCTION)) {
            reportFeature(FeatureId.WIND_REDUCTION, val);
        } else if (featId == OppoProtocol.FEAT_SPATIAL_AUDIO && spatialMode43 && spatialSpec != null) {
            spatialConfirmed = true;
            reportFeature(FeatureId.SPATIAL_AUDIO, val);
        }
    }

    private void parseFindBudsAck(byte[] payload) {
        if (payload == null || payload.length < 1) return;
        int status = payload[0] & 0xFF;
        if (status == 0x00 && findBudsSpec != null && protocolReady) {
            reportFeature(FeatureId.FIND_MY_BUDS, pendingFindTarget);
        } else if (status != 0x00) {
            trace("OppoClient", "find buds ack status=0x" + Integer.toHexString(status));
        }
    }

    private void parseBattery(byte[] payload, boolean event) {
        int[] value = OppoProtocol.battery(payload, event);
        if (value == null) return;
        if (protocolReady) events().onBattery(value[0], value[1], value[2], value[3]);
        else initialBattery = value;
    }

    private void parseEvent(byte[] payload) {
        if (payload.length < 2) return;
        int subtype = payload[0] & 0xFF;
        if (subtype == 0x03) {
            // ANC 事件: 03 01 01 <mask LE>; 03 02 01 ... 是查询回声, 忽略
            if (payload.length == 5 && payload[1] == 0x01 && payload[2] == 0x01) {
                int mask = (payload[3] & 0xFF) | ((payload[4] & 0xFF) << 8);
                fireAncMask(mask);
            }
        } else if (subtype == 0x01) {
            parseBattery(payload, true);
        } else if (subtype == 0x02) {
            parseWear(payload, true);
        } else if (subtype == 0x05) {
            int val = OppoProtocol.lowLatencyEvent(payload);
            if (val >= 0 && (lowLatencySpec != null || features010D.containsKey(OppoProtocol.FEAT_LOW_LATENCY))) {
                features010D.put(OppoProtocol.FEAT_LOW_LATENCY, val);
                if (protocolReady && lowLatencySpec != null) {
                    reportFeature(FeatureId.LOW_LATENCY, val);
                }
            }
        }
    }

    private void parseAncState(byte[] payload) {
        int mask = OppoProtocol.noiseMask(payload);
        if (mask >= 0) fireAncMask(mask);
    }

    private static int matchOptionMask(FeatureDescriptor spec, int mask) {
        if (spec == null || mask < 0) return -1;
        if (spec.findOption(mask) != null) return mask;
        // Support combined bitmasks (e.g. generic ANC 0x02 ORed with specific level 0x10/0x20/0x40/0x80/0x0800)
        for (int i = spec.options.length - 1; i >= 0; i--) {
            OptionItem opt = spec.options[i];
            if (opt.id > 0 && (mask & opt.id) == opt.id) {
                return opt.id;
            }
        }
        return -1;
    }

    private void maybeConfirmDynamicAncSpec(int mask, boolean emitNow) {
        if (ancLevelSpec != null || productId < 0 || productId == OppoProtocol.PID_REALME_AIR7_PRO) return;
        if ((mask & 0x08F0) == 0) return;
        boolean hasAdaptive = (mask & 0x0800) != 0;
        OptionItem[] opts = hasAdaptive
                ? OppoProtocol.ancLevelOptions(OppoProtocol.PID_ENCO_FREE4)
                : OppoProtocol.ancLevelOptions(OppoProtocol.PID_ENCO_AIR5_PRO);
        if (opts.length == 0) return;
        ancLevelSpec = FeatureDescriptor.choice(FeatureId.ANC_LEVEL, VERIF_UNTESTED,
                FeatureDescriptor.SAFETY_NONE, 1 << MODE_ANC, false, opts,
                "仅降噪模式", "ANC only");
        if (emitNow) {
            int matched = matchOptionMask(ancLevelSpec, mask);
            if (matched >= 0) confirmFeature(ancLevelSpec, matched);
            else confirmFeature(ancLevelSpec);
        }
    }

    private void fireAncMask(int mask) {
        int ui = OppoProtocol.noiseMode(mask);
        if (ui < 0) return;
        trace("OppoClient", "anc mask=0x" + Integer.toHexString(mask) + " ui=" + ui);
        if (protocolReady) {
            noiseConfirmed = true;
            maybeConfirmDynamicAncSpec(mask, true);
            events().onNoiseMode(ui);
            int matchedAnc = matchOptionMask(ancLevelSpec, mask);
            if (matchedAnc >= 0) {
                reportFeature(FeatureId.ANC_LEVEL, matchedAnc);
            }
            int matchedTrans = matchOptionMask(transModeSpec, mask);
            if (matchedTrans >= 0) {
                reportFeature(FeatureId.TRANS_MODE, matchedTrans);
            }
        } else {
            initialMode = ui;
            initialAncMask = mask;
            maybeConfirmDynamicAncSpec(mask, false);
        }
    }

    private void parseAncAck(byte[] payload) {
        if (payload.length >= 1) {
            int status = payload[0] & 0xFF;
            if (status != 0x00) {
                trace("OppoClient", "anc set ack status=0x" + Integer.toHexString(status));
                if (status == 0x0E) {
                    // 未佩戴时切换可能被拒绝, 读回真实状态
                    queryNoise();
                }
            }
        }
    }

    // ---- 工具 ----

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
    @Override public int potentialCaps() {
        return caps() | CAP_NOISE
                | (eqSpec != null ? CAP_EQUALIZER : 0)
                | (spatialSpec != null ? CAP_SPATIAL : 0);
    }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            if (name == null) return false;
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.contains("oppo")
                    || (n.contains("enco") && !n.contains("encore"))
                    || n.contains("oneplus")
                    || n.contains("realme");
        }
        @Override public WireDriver create() { return new OppoOpoClient(); }
    }

}

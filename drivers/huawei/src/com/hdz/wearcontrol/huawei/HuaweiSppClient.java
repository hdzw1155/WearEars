package com.hdz.wearcontrol.huawei;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.FeatureDescriptor;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.OptionItem;
import com.hdz.wearcontrol.SoundPreset;
import com.hdz.wearcontrol.WireDriver;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 华为 FreeBuds / HONOR 的 SPP 协议客户端(RFCOMM)。
 *
 * 帧格式: 5A | len(2B BE) | 00 | group(1) | cmd(1) | params | CRC16-XMODEM(2B BE)
 *   len = 参数区长度 + 3, 总长 = len + 5, CRC 覆盖 CRC 之前的全部字节。
 *   TLV: type(1) | len(1) | value。
 *
 * 电量: group 01 cmd 08 查询(参数为空 TLV 01/02/03), 上报 cmd 27。
 *   TLV01 = 整机电量; TLV02 = [左, 右, 仓](0 表示未连接); TLV03 = 充电位(整机级)。
 *
 * 降噪: group 2B cmd 04 写, 参数 TLV1 = [01, 02, mode, level]
 *   mode: 00 关 / 01 降噪 / 02 通透; level: mode=0 用 00, 否则 FF (子档位写入时传档位字节)。
 *   group 2B cmd 2A 读, 参数 TLV1/02; 应答 TLV1 = [level, mode](mode 在第二字节)。
 *   写没有回执, 400ms 后补一次 2A 读回确认; 耳机按键改档会主动发 2B 03。
 *
 * SE4 的 SPP 监听在信道 1(旧款 5i/4i 在 16), SDP 找不到时反射 createRfcommSocket 兜底。
 */
public class HuaweiSppClient extends WireDriver {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int GROUP_BATTERY = HuaweiProtocol.GROUP_BATTERY;
    private static final int GROUP_ANC = HuaweiProtocol.GROUP_ANC;

    private static final int CMD_BATTERY_QUERY = HuaweiProtocol.CMD_BATTERY_QUERY;
    private static final int CMD_BATTERY_REPORT = HuaweiProtocol.CMD_BATTERY_REPORT;
    private static final int CMD_ANC_SWITCH = HuaweiProtocol.CMD_ANC_SWITCH;
    private static final int CMD_ANC_QUERY = HuaweiProtocol.CMD_ANC_QUERY;
    private static final int CMD_ANC_REPORT = HuaweiProtocol.CMD_ANC_REPORT;

    private static final int CHANNEL_SE4 = 1;
    private static final int CHANNEL_LEGACY = 16;

    private static final int MAX_FRAME = 1024;

    private static final byte[] BATTERY_QUERY_PARAMS = {
            0x01, 0x00, 0x02, 0x00, 0x03, 0x00
    };
    private static final byte[] ANC_QUERY_PARAMS = {
            0x01, 0x00, 0x02, 0x00
    };
    private static final byte[] DEVICE_INFO_PARAMS = {
            0x03, 0x00, 0x07, 0x00, 0x0F, 0x00
    };
    private static final byte[] LOW_LATENCY_QUERY_PARAMS = {
            0x02, 0x00
    };
    private static final byte[] WEAR_QUERY_PARAMS = {
            0x01, 0x00
    };
    private static final byte[] LIVE_WEAR_QUERY_PARAMS = {
            0x01, 0x00, 0x02, 0x00
    };
    private static final byte[] DUAL_QUERY_PARAMS = {
            0x01, 0x00
    };

    private final byte[] stage = new byte[2048];
    private int stageLen;
    private volatile boolean noiseConfirmed, singleBattery;
    private volatile HuaweiSoundProtocol.State equalizer;
    private volatile String connectedName = "";
    private volatile int wearLeft = -1, wearRight = -1;

    private volatile FeatureDescriptor ancLevelSpec;
    private volatile FeatureDescriptor transModeSpec;
    private volatile FeatureDescriptor windSpec;
    private volatile FeatureDescriptor spatialSpec;
    private volatile FeatureDescriptor spatialSceneSpec;
    private volatile FeatureDescriptor equalizerSpec;
    private volatile FeatureDescriptor lowLatencySpec;
    private volatile FeatureDescriptor multipointSpec;
    private volatile FeatureDescriptor wearDetectSpec;
    private volatile FeatureDescriptor wearStatusSpec;

    @Override protected String logTag() { return "HwClient"; }
    @Override protected String unnamedDevice() { return null; }
    @Override protected boolean cancelDiscovery() { return true; }
    @Override protected String connectionFailure() { return "找不到 SPP 服务(信道 1/16)，耳机需先与手表配对"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{SPP_UUID}; }
    @Override protected int[] channels() { return new int[]{CHANNEL_SE4, CHANNEL_LEGACY}; }
    @Override protected void prepare() {
        noiseConfirmed = singleBattery = false;
        equalizer = null;
        connectedName = deviceName();
        stageLen = 0;
        wearLeft = wearRight = -1;
        ancLevelSpec = transModeSpec = windSpec = spatialSpec = spatialSceneSpec = null;
        equalizerSpec = lowLatencySpec = multipointSpec = wearDetectSpec = wearStatusSpec = null;
    }
    @Override protected void opened() {
        ready();
        queryBattery();
        queryInitialFeatures();
        schedule(new Runnable() { @Override public void run() { queryNoise(); }}, 250);
    }

    private void queryInitialFeatures() {
        writeFrame(GROUP_BATTERY, HuaweiProtocol.CMD_DEVICE_INFO, DEVICE_INFO_PARAMS);
        writeFrame(GROUP_ANC, HuaweiProtocol.CMD_FEATURE_B4, HuaweiProtocol.windQueryParams());
        writeFrame(GROUP_ANC, HuaweiProtocol.CMD_FEATURE_B4, HuaweiProtocol.spatialQueryParams());
        writeFrame(GROUP_ANC, HuaweiProtocol.CMD_LOW_LATENCY, LOW_LATENCY_QUERY_PARAMS);
        writeFrame(GROUP_ANC, HuaweiProtocol.CMD_WEAR_READ, WEAR_QUERY_PARAMS);
        writeFrame(GROUP_ANC, HuaweiProtocol.CMD_LIVE_WEAR, LIVE_WEAR_QUERY_PARAMS);
        if (HuaweiProtocol.supportsMultipoint(connectedName)) {
            writeFrame(GROUP_ANC, HuaweiProtocol.CMD_DUAL_READ, DUAL_QUERY_PARAMS);
        }
    }

    @Override
    public String brandName() {
        return "Huawei";
    }

    @Override
    public int caps() {
        return (singleBattery ? CAP_BATTERY_SINGLE : CAP_BATTERY)
                | (noiseConfirmed ? CAP_NOISE : 0) | (equalizer != null ? CAP_EQUALIZER : 0);
    }

    @Override
    public int verifLevel() {
        // FreeBuds 6i / FreeBuds SE 4 ANC / HONOR Earbuds X9 已在本项目真机验证;
        // 其他华为/荣耀型号沿用同一 SPP 协议族, 如实标实验性。
        String n = HuaweiProtocol.normalize(connectedName);
        if (n.equals("huaweifreebuds6i") || n.equals("freebuds6i")
                || n.equals("huaweifreebudsse4anc") || n.equals("freebudsse4anc")
                || n.equals("honorearbudsx9") || n.equals("earbudsx9")) {
            return VERIF_REAL;
        }
        return VERIF_UNTESTED;
    }

    // ---- 命令 ----

    @Override
    public void queryBattery() {
        writeFrame(GROUP_BATTERY, CMD_BATTERY_QUERY, BATTERY_QUERY_PARAMS);
    }

    @Override
    public void queryNoise() {
        writeFrame(GROUP_ANC, CMD_ANC_QUERY, ANC_QUERY_PARAMS);
    }

    @Override
    public void changeNoise(int mode, Runnable flushed) {
        if (!noiseConfirmed) return;
        if (mode != MODE_ANC && mode != MODE_OFF && mode != MODE_TRANS) return;
        int wire;
        if (mode == MODE_ANC) wire = 0x01;
        else if (mode == MODE_TRANS) wire = 0x02;
        else wire = 0x00;
        byte level = (byte) (wire == 0x00 ? 0x00 : 0xFF);
        byte[] params = {0x01, 0x02, (byte) wire, level};
        writeThen(frame(GROUP_ANC, CMD_ANC_SWITCH, params), () -> {
            if (flushed != null) flushed.run();
            schedule(this::queryNoise, 400);
        });
    }

    @Override
    public void querySpatial() {
        if (spatialSpec != null) {
            writeFrame(GROUP_ANC, HuaweiProtocol.CMD_FEATURE_B4, HuaweiProtocol.spatialQueryParams());
        }
    }

    @Override
    public void setSpatial(boolean enabled) {
        writeFeature(FeatureId.SPATIAL_AUDIO, enabled ? 1 : 0, null);
    }

    @Override
    public FeatureDescriptor[] featureDescriptors() {
        List<FeatureDescriptor> list = new ArrayList<>();
        if (ancLevelSpec != null) list.add(ancLevelSpec);
        if (transModeSpec != null) list.add(transModeSpec);
        if (windSpec != null) list.add(windSpec);
        if (spatialSpec != null) list.add(spatialSpec);
        if (spatialSceneSpec != null) list.add(spatialSceneSpec);
        if (equalizerSpec != null) list.add(equalizerSpec);
        if (lowLatencySpec != null) list.add(lowLatencySpec);
        if (multipointSpec != null) list.add(multipointSpec);
        if (wearDetectSpec != null) list.add(wearDetectSpec);
        if (wearStatusSpec != null) list.add(wearStatusSpec);
        return list.toArray(new FeatureDescriptor[0]);
    }

    @Override
    public void queryFeature(FeatureId id) {
        if (id == null) return;
        switch (id) {
            case NOISE_MODE:
            case ANC_LEVEL:
            case TRANS_MODE:
                queryNoise();
                break;
            case WIND_REDUCTION:
                writeFrame(GROUP_ANC, HuaweiProtocol.CMD_FEATURE_B4, HuaweiProtocol.windQueryParams());
                break;
            case SPATIAL_AUDIO:
            case SPATIAL_SCENE:
                writeFrame(GROUP_ANC, HuaweiProtocol.CMD_FEATURE_B4, HuaweiProtocol.spatialQueryParams());
                break;
            case EQUALIZER:
                queryEqualizer();
                break;
            case LOW_LATENCY:
                writeFrame(GROUP_ANC, HuaweiProtocol.CMD_LOW_LATENCY, LOW_LATENCY_QUERY_PARAMS);
                break;
            case MULTIPOINT:
                if (HuaweiProtocol.supportsMultipoint(connectedName)) {
                    writeFrame(GROUP_ANC, HuaweiProtocol.CMD_DUAL_READ, DUAL_QUERY_PARAMS);
                }
                break;
            case WEAR_DETECTION:
                writeFrame(GROUP_ANC, HuaweiProtocol.CMD_WEAR_READ, WEAR_QUERY_PARAMS);
                break;
            case WEAR_STATUS:
                writeFrame(GROUP_ANC, HuaweiProtocol.CMD_LIVE_WEAR, LIVE_WEAR_QUERY_PARAMS);
                break;
            case FIRMWARE_INFO:
                writeFrame(GROUP_BATTERY, HuaweiProtocol.CMD_DEVICE_INFO, DEVICE_INFO_PARAMS);
                break;
            default:
                break;
        }
    }

    @Override
    public void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (id == null) return;
        switch (id) {
            case NOISE_MODE:
                changeNoise(value, flushed);
                break;
            case ANC_LEVEL:
                if (noiseConfirmed && ancLevelSpec != null && ancLevelSpec.isValidValue(value)) {
                    byte[] params = {0x01, 0x02, 0x01, (byte) value};
                    writeThen(frame(GROUP_ANC, CMD_ANC_SWITCH, params), () -> {
                        if (flushed != null) flushed.run();
                        schedule(this::queryNoise, 400);
                    });
                }
                break;
            case TRANS_MODE:
                if (noiseConfirmed && transModeSpec != null && transModeSpec.isValidValue(value)) {
                    byte[] params = {0x01, 0x02, 0x02, (byte) value};
                    writeThen(frame(GROUP_ANC, CMD_ANC_SWITCH, params), () -> {
                        if (flushed != null) flushed.run();
                        schedule(this::queryNoise, 400);
                    });
                }
                break;
            case WIND_REDUCTION:
                if (windSpec != null && (value == 0 || value == 1)) {
                    writeThen(frame(GROUP_ANC, HuaweiProtocol.CMD_FEATURE_B4,
                            HuaweiProtocol.windWriteParams(value != 0)), () -> {
                        if (flushed != null) flushed.run();
                        schedule(() -> queryFeature(FeatureId.WIND_REDUCTION), 300);
                    });
                }
                break;
            case SPATIAL_AUDIO:
                if (spatialSpec != null && spatialSpec.isValidValue(value)) {
                    writeThen(frame(GROUP_ANC, HuaweiProtocol.CMD_FEATURE_B4,
                            HuaweiProtocol.spatialModeWriteParams(value)), () -> {
                        if (flushed != null) flushed.run();
                        schedule(this::querySpatial, 300);
                    });
                }
                break;
            case SPATIAL_SCENE:
                if (spatialSceneSpec != null && spatialSceneSpec.isValidValue(value)) {
                    writeThen(frame(GROUP_ANC, HuaweiProtocol.CMD_FEATURE_B4,
                            HuaweiProtocol.spatialSceneWriteParams(value)), () -> {
                        if (flushed != null) flushed.run();
                        schedule(this::querySpatial, 300);
                    });
                }
                break;
            case EQUALIZER:
                for (SoundPreset option : equalizerPresets()) {
                    if (option.id == value) {
                        writeThen(frame(GROUP_ANC, HuaweiProtocol.CMD_EQ_WRITE,
                                new byte[]{1, 1, (byte) value}), flushed);
                        return;
                    }
                }
                break;
            case LOW_LATENCY:
                if (lowLatencySpec != null && (value == 0 || value == 1)) {
                    writeThen(frame(GROUP_ANC, HuaweiProtocol.CMD_LOW_LATENCY,
                            new byte[]{0x01, 0x01, (byte) value}), () -> {
                        if (flushed != null) flushed.run();
                        schedule(() -> queryFeature(FeatureId.LOW_LATENCY), 300);
                    });
                }
                break;
            case WEAR_DETECTION:
                if (wearDetectSpec != null && (value == 0 || value == 1)) {
                    writeThen(frame(GROUP_ANC, HuaweiProtocol.CMD_WEAR_WRITE,
                            new byte[]{0x01, 0x01, (byte) value}), () -> {
                        if (flushed != null) flushed.run();
                        schedule(() -> queryFeature(FeatureId.WEAR_DETECTION), 300);
                    });
                }
                break;
            case MULTIPOINT:
                if (HuaweiProtocol.supportsMultipoint(connectedName)
                        && multipointSpec != null && (value == 0 || value == 1)) {
                    writeThen(frame(GROUP_ANC, HuaweiProtocol.CMD_DUAL_WRITE,
                            new byte[]{0x01, 0x01, (byte) value}), () -> {
                        if (flushed != null) flushed.run();
                        schedule(() -> queryFeature(FeatureId.MULTIPOINT), 300);
                    });
                }
                break;
            default:
                break;
        }
    }

    /** 组帧并发送: 5A len(2B BE) 00 group cmd params CRC16(大端) */
    private void writeFrame(int group, int cmd, byte[] params) { write(frame(group, cmd, params)); }

    private static byte[] frame(int group, int cmd, byte[] params) {
        return HuaweiProtocol.frame(group, cmd, params);
    }

    /** CRC16/XMODEM: poly=0x1021, init=0, 无反射, 无最终 XOR */
    static int crc16Xmodem(byte[] data, int off, int len) {
        return HuaweiProtocol.crc16Xmodem(data, off, len);
    }

    // ---- 接收 ----

    @Override protected void received(byte[] data) {
        if (data == null || data.length == 0) return;
        int pos = 0;
        while (pos < data.length) {
            if (!transportOpen()) return;
            if (stageLen >= stage.length) {
                stageLen = 0;
                trace("HwClient", "RFCOMM receive buffer overflow");
            }
            int chunk = Math.min(data.length - pos, stage.length - stageLen);
            System.arraycopy(data, pos, stage, stageLen, chunk);
            stageLen += chunk;
            pos += chunk;
            parseStage();
        }
    }

    private void parseStage() {
        int off = 0;
        while (true) {
            int head = findHead(stage, off, stageLen);
            if (head < 0) {
                off = stageLen;
                break;
            }
            if (head + 5 > stageLen) {
                off = head;
                break;
            }
            int len = ((stage[head + 1] & 0xFF) << 8) | (stage[head + 2] & 0xFF);
            int total = len + 5;
            if ((stage[head + 3] & 0xFF) != 0x00 || len < 3 || total > MAX_FRAME) {
                off = head + 1;
                continue;
            }
            if (head + total > stageLen) {
                off = head;
                break;
            }
            int expect = ((stage[head + total - 2] & 0xFF) << 8)
                    | (stage[head + total - 1] & 0xFF);
            if (crc16Xmodem(stage, head, total - 2) == expect) {
                dispatchFrame(stage, head, total);
                off = head + total;
            } else {
                off = head + 1;
            }
            if (off >= stageLen) break;
        }
        if (off > 0) {
            if (off < stageLen) {
                System.arraycopy(stage, off, stage, 0, stageLen - off);
                stageLen -= off;
            } else {
                stageLen = 0;
            }
        }
        if (stageLen == stage.length) stageLen = 0;
    }

    private static int findHead(byte[] b, int from, int len) {
        for (int i = from; i < len; i++) {
            if ((b[i] & 0xFF) == 0x5A) return i;
        }
        return -1;
    }

    private void dispatchFrame(byte[] b, int head, int total) {
        int group = b[head + 4] & 0xFF;
        int cmd = b[head + 5] & 0xFF;
        int paramsOff = head + 6;
        int paramsLen = total - 8;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            sb.append(String.format("%02X ", b[head + i]));
        }
        trace("HwClient", "rx g=0x" + Integer.toHexString(group)
                + " c=0x" + Integer.toHexString(cmd) + " [" + sb.toString().trim() + "]");

        if (group == GROUP_BATTERY
                && (cmd == CMD_BATTERY_QUERY || cmd == CMD_BATTERY_REPORT)) {
            parseBattery(b, paramsOff, paramsLen);
        } else if (group == GROUP_BATTERY && cmd == HuaweiProtocol.CMD_DEVICE_INFO) {
            String fw = HuaweiProtocol.decodeFirmwareInfo(b, paramsOff, paramsLen);
            if (!fw.isEmpty()) reportFeatureText(FeatureId.FIRMWARE_INFO, fw);
        } else if (group == GROUP_ANC && cmd == CMD_ANC_QUERY) {
            parseAncState(b, paramsOff, paramsLen);
        } else if (group == GROUP_ANC && cmd == CMD_ANC_REPORT) {
            parseAncPush(b, paramsOff, paramsLen);
        } else if (group == GROUP_ANC && cmd == HuaweiProtocol.CMD_EQ_QUERY
                && HuaweiSoundProtocol.supportsQuery(connectedName)) {
            if (!HuaweiProtocol.tlvsValidAndOk(b, paramsOff, paramsLen)) return;
            HuaweiSoundProtocol.State report = HuaweiSoundProtocol.decode(
                    b, paramsOff, paramsLen, connectedName);
            if (report != null) {
                equalizer = report;
                FeatureDescriptor spec = FeatureDescriptor.choice(
                        FeatureId.EQUALIZER, verifLevel(), FeatureDescriptor.SAFETY_NONE,
                        0, false, OptionItem.fromPresets(report.presets), "", "");
                equalizerSpec = spec;
                confirmFeature(spec);
                events().onEqualizer(report.current);
            }
        } else if (group == GROUP_ANC && cmd == HuaweiProtocol.CMD_FEATURE_B4) {
            parseFeatureB4(b, paramsOff, paramsLen);
        } else if (group == GROUP_ANC && cmd == HuaweiProtocol.CMD_LOW_LATENCY) {
            int val = HuaweiProtocol.decodeTlvBool(b, paramsOff, paramsLen, 2);
            if (val >= 0) {
                FeatureDescriptor spec = lowLatencySpec;
                if (spec == null) {
                    spec = FeatureDescriptor.toggle(FeatureId.LOW_LATENCY, VERIF_UNTESTED, false);
                    lowLatencySpec = spec;
                }
                confirmFeature(spec, val);
            }
        } else if (group == GROUP_ANC && cmd == HuaweiProtocol.CMD_WEAR_READ) {
            int val = HuaweiProtocol.decodeTlvBool(b, paramsOff, paramsLen, 1);
            if (val >= 0) {
                FeatureDescriptor spec = wearDetectSpec;
                if (spec == null) {
                    spec = FeatureDescriptor.toggle(FeatureId.WEAR_DETECTION, VERIF_UNTESTED, false);
                    wearDetectSpec = spec;
                }
                confirmFeature(spec, val);
            }
        } else if (group == GROUP_ANC && cmd == HuaweiProtocol.CMD_LIVE_WEAR) {
            int[] wear = HuaweiProtocol.decodeLiveWear(b, paramsOff, paramsLen, 1, 2);
            if (wear != null) {
                if (wear[0] >= 0) wearLeft = wear[0];
                if (wear[1] >= 0) wearRight = wear[1];
                publishWearStatus();
            }
        } else if (group == GROUP_ANC && cmd == HuaweiProtocol.CMD_DUAL_READ
                && HuaweiProtocol.supportsMultipoint(connectedName)) {
            int val = HuaweiProtocol.decodeTlvBool(b, paramsOff, paramsLen, 1);
            if (val >= 0) {
                FeatureDescriptor spec = multipointSpec;
                if (spec == null) {
                    spec = FeatureDescriptor.toggle(FeatureId.MULTIPOINT, VERIF_UNTESTED, false);
                    multipointSpec = spec;
                }
                confirmFeature(spec, val);
            }
        }
    }

    private void parseFeatureB4(byte[] b, int off, int len) {
        if (!HuaweiProtocol.tlvsValidAndOk(b, off, len)) return;
        int feature = HuaweiProtocol.findTlvByte(b, off, len, 1);
        if (feature == HuaweiProtocol.FEATURE_WIND) {
            int wind = HuaweiProtocol.decodeWindReply(b, off, len);
            if (wind >= 0) {
                FeatureDescriptor spec = windSpec;
                if (spec == null) {
                    spec = FeatureDescriptor.toggle(FeatureId.WIND_REDUCTION, VERIF_UNTESTED, false);
                    windSpec = spec;
                }
                confirmFeature(spec, wind);
            }
        } else if (feature == HuaweiProtocol.FEATURE_SPATIAL) {
            int[] spatial = HuaweiProtocol.decodeSpatialReply(b, off, len);
            if (spatial != null) {
                if (spatial[0] >= 0) {
                    FeatureDescriptor spec = spatialSpec;
                    if (spec == null) {
                        spec = FeatureDescriptor.choice(
                                FeatureId.SPATIAL_AUDIO, VERIF_UNTESTED,
                                FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true,
                                HuaweiProtocol.SPATIAL_OPTIONS, "", "");
                        spatialSpec = spec;
                    }
                    confirmFeature(spec, spatial[0]);
                }
                if (spatial[1] >= 0) {
                    FeatureDescriptor sceneSpec = spatialSceneSpec;
                    if (sceneSpec == null) {
                        sceneSpec = FeatureDescriptor.choice(
                                FeatureId.SPATIAL_SCENE, VERIF_UNTESTED,
                                FeatureDescriptor.SAFETY_AUDIO_WARN, 0, true,
                                HuaweiProtocol.SPATIAL_SCENE_OPTIONS, "", "");
                        spatialSceneSpec = sceneSpec;
                    }
                    confirmFeature(sceneSpec, spatial[1]);
                }
            }
        }
    }

    private void parseAncPush(byte[] b, int off, int len) {
        if (!HuaweiProtocol.tlvsValidAndOk(b, off, len)) return;
        boolean hadAnc = parseAncState(b, off, len);
        int[] wear = HuaweiProtocol.decodeLiveWear(b, off, len, 8, 9);
        if (wear != null) {
            if (wear[0] >= 0) wearLeft = wear[0];
            if (wear[1] >= 0) wearRight = wear[1];
            publishWearStatus();
        } else if (!hadAnc) {
            // 耳机按键改了档位但未附带完整 TLV1, 重新查询一次
            queryNoise();
        }
    }

    private void publishWearStatus() {
        int mask = (wearLeft == 1 ? 1 : 0) | (wearRight == 1 ? 2 : 0);
        FeatureDescriptor spec = wearStatusSpec;
        if (spec == null) {
            spec = FeatureDescriptor.readOnly(FeatureId.WEAR_STATUS, VERIF_UNTESTED, false);
            wearStatusSpec = spec;
        }
        confirmFeature(spec, mask);
    }

    /** 电量 TLV: 01 整机电量 / 02 左右仓电量 / 03 充电位(分部件或整机) */
    private void parseBattery(byte[] b, int off, int len) {
        int end = off + len;
        int p = off;
        int overall = -1;
        int l = 0xFF, r = 0xFF, c = 0xFF;
        boolean hasTlv2 = false;
        int flags = 0;
        while (p + 2 <= end) {
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) break;
            if (type == 1 && tlen >= 1) {
                overall = b[v] & 0xFF;
            } else if (type == 2 && tlen >= 3) {
                hasTlv2 = true;
                l = b[v] & 0xFF;
                r = b[v + 1] & 0xFF;
                c = b[v + 2] & 0xFF;
            } else if (type == 3) {
                int count = Math.min(tlen, 3);
                for (int i = 0; i < count; i++) {
                    if ((b[v + i] & 0x01) != 0) flags |= (1 << i);
                }
            }
            p = v + tlen;
        }
        int nl = normPct(l), nr = normPct(r), nc = normPct(c);
        if (!hasTlv2 && overall >= 0 && overall <= 100) {
            int ov = normPct(overall);
            if (ov != 0xFF) singleBattery = true;
            nl = ov;
            nr = 0xFF;
            nc = 0xFF;
        } else if (hasTlv2) {
            singleBattery = false;
            if (nl == 0xFF && nr == 0xFF && nc == 0xFF && overall >= 0 && overall <= 100) {
                nl = normPct(overall);
            }
        }
        trace("HwClient", "battery overall=" + overall + " L/R/C=" + nl + "/" + nr + "/" + nc
                + " flags=" + flags);
        events().onBattery(nl, nr, nc, flags);
    }

    /** ANC 读回应答: TLV1 = [level, mode], mode 在第二字节 */
    private boolean parseAncState(byte[] b, int off, int len) {
        if (!HuaweiProtocol.tlvsValidAndOk(b, off, len)) return false;
        int end = off + len;
        int p = off;
        while (p + 2 <= end) {
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) break;
            if (type == 1 && tlen >= 2) {
                int level = b[v] & 0xFF;
                int mode = b[v + 1] & 0xFF;
                int ui;
                if (mode == 0x01) ui = MODE_ANC;
                else if (mode == 0x02) ui = MODE_TRANS;
                else if (mode == 0x00) ui = MODE_OFF;
                else ui = -1;
                if (ui >= 0) {
                    noiseConfirmed = true;
                    ensureNoiseSubSpecs();
                }
                trace("HwClient", "anc level=" + level + " mode=" + mode + " ui=" + ui);
                events().onNoiseMode(ui);
                if (ui == MODE_ANC) {
                    OptionItem[] opts = HuaweiProtocol.ancLevelOptions(connectedName);
                    if (opts != null) {
                        FeatureDescriptor spec = ensureAncLevelSpec(opts);
                        if (containsOption(opts, level)) {
                            confirmFeature(spec, level);
                        } else {
                            confirmFeature(spec);
                        }
                    }
                } else if (ui == MODE_TRANS) {
                    OptionItem[] opts = HuaweiProtocol.transModeOptions(connectedName);
                    if (opts != null) {
                        FeatureDescriptor spec = ensureTransModeSpec(opts);
                        if (containsOption(opts, level)) {
                            confirmFeature(spec, level);
                        } else {
                            confirmFeature(spec);
                        }
                    }
                }
                return true;
            }
            p = v + tlen;
        }
        return false;
    }

    private void ensureNoiseSubSpecs() {
        OptionItem[] ancOpts = HuaweiProtocol.ancLevelOptions(connectedName);
        if (ancOpts != null && ancLevelSpec == null) {
            confirmFeature(ensureAncLevelSpec(ancOpts));
        }
        OptionItem[] transOpts = HuaweiProtocol.transModeOptions(connectedName);
        if (transOpts != null && transModeSpec == null) {
            confirmFeature(ensureTransModeSpec(transOpts));
        }
    }

    private FeatureDescriptor ensureAncLevelSpec(OptionItem[] opts) {
        FeatureDescriptor spec = ancLevelSpec;
        if (spec == null) {
            spec = FeatureDescriptor.choice(
                    FeatureId.ANC_LEVEL, VERIF_UNTESTED,
                    FeatureDescriptor.SAFETY_NONE, 1 << MODE_ANC, false,
                    opts, "", "");
            ancLevelSpec = spec;
        }
        return spec;
    }

    private FeatureDescriptor ensureTransModeSpec(OptionItem[] opts) {
        FeatureDescriptor spec = transModeSpec;
        if (spec == null) {
            spec = FeatureDescriptor.choice(
                    FeatureId.TRANS_MODE, VERIF_UNTESTED,
                    FeatureDescriptor.SAFETY_NONE, 1 << MODE_TRANS, false,
                    opts, "", "");
            transModeSpec = spec;
        }
        return spec;
    }

    private static boolean containsOption(OptionItem[] opts, int id) {
        for (OptionItem opt : opts) {
            if (opt.id == id) return true;
        }
        return false;
    }

    private static int normPct(int v) {
        if (v >= 1 && v <= 100) return v;
        return 0xFF;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
    @Override public int potentialCaps() {
        return caps() | CAP_NOISE | (HuaweiSoundProtocol.supportsQuery(connectedName) ? CAP_EQUALIZER : 0);
    }

    @Override public com.hdz.wearcontrol.SoundPreset[] equalizerPresets() {
        HuaweiSoundProtocol.State report = equalizer;
        return report == null ? new com.hdz.wearcontrol.SoundPreset[0] : report.presets.clone();
    }
    @Override public void queryEqualizer() {
        if (HuaweiSoundProtocol.supportsQuery(connectedName))
            writeFrame(GROUP_ANC, HuaweiProtocol.CMD_EQ_QUERY, new byte[]{1, 0, 2, 0, 3, 0});
    }
    @Override public void setEqualizer(int preset) {
        writeFeature(FeatureId.EQUALIZER, preset, null);
    }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            if (name == null) return false;
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.contains("freebuds") || n.contains("freeclip") || n.contains("huawei")
                    || n.contains("honor") || n.contains("华为") || n.contains("荣耀");
        }
        @Override public WireDriver create() { return new HuaweiSppClient(); }
    }

}

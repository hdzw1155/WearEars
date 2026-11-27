package com.hdz.wearcontrol;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 漫步者(EDIFIER)客户端(实验性).
 *
 * 协议依据: HyperEars/docs/edifier-bes-protocol.md、mEDIFIER、BudsLink 三份开源实现 + 社区抓包.
 * V2 帧: AA | EC | CMD | LEN_H | LEN_L | PAYLOAD | CRC8 ; payload 默认 XOR 0xA5; CRC8=sum(前序线上字节)&FF
 * V1 帧: AA | LEN | CMD | DATA | CRC16_H | CRC16_L ; LEN=len(DATA)+1; CRC16=(0x2019+sum)&FFFF
 * 电量: D0 单值；F2 只发布已证实的左右耳字段，盒与充电标志保持未知。
 * 降噪: CC 查询拿 [group][mode] → C1 设置; 支持 0x10/0x1B 降噪深度与抗风噪; 每个 group 的 mode 含义不同, 不猜 group.
 * 游戏模式: 仅在显式 queryFeature/writeFeature(LOW_LATENCY) 时发 0x08/0x09(绝不在初始探测或切三态中发出).
 * 严禁发送 0x49 LDAC 切换指令(触发蓝牙重连).
 * 传输: SPP(EDF00000-...) 优先, 失败走 BLE GATT(特征 48090001/48090002, 按 UUID 找, 不强绑服务).
 */
public class EdifierClient extends WireDriver {

    private static final String TAG = "Edifier";

    private static final UUID SPP_UUID = UUID.fromString("edf00000-edfe-dfed-fedf-edfedfedfedf");
    private static final UUID BLE_NOTIFY = UUID.fromString("48090001-1a48-11e9-ab14-d663bd873d93");
    private static final UUID BLE_WRITE = UUID.fromString("48090002-1a48-11e9-ab14-d663bd873d93");
    private static final int CMD_BATT_D0 = 0xD0;
    private static final int CMD_BATT_F2 = 0xF2;
    private static final int CMD_ANC_QUERY = 0xCC;
    private static final int CMD_ANC_SET = 0xC1;
    private static final int CMD_GAME_QUERY = 0x08;
    private static final int CMD_GAME_SET = 0x09;

    private static final OptionItem[] ANC_OPTIONS_10 = {
            new OptionItem(1, "深度降噪", "Deep", OptionItem.ROLE_DEFAULT),
            new OptionItem(2, "舒适降噪", "Comfort", OptionItem.ROLE_DEFAULT),
            new OptionItem(3, "抗风噪", "Wind", OptionItem.ROLE_WIND)
    };
    private static final OptionItem[] ANC_OPTIONS_1B = {
            new OptionItem(1, "深度降噪", "Deep", OptionItem.ROLE_DEFAULT),
            new OptionItem(2, "舒适降噪", "Comfort", OptionItem.ROLE_DEFAULT),
            new OptionItem(3, "轻度降噪", "Mild", OptionItem.ROLE_DEFAULT),
            new OptionItem(4, "抗风噪", "Wind", OptionItem.ROLE_WIND)
    };
    private static final FeatureDescriptor ANC_SPEC_10 = FeatureDescriptor.choice(
            FeatureId.ANC_LEVEL, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false, ANC_OPTIONS_10);
    private static final FeatureDescriptor ANC_SPEC_1B = FeatureDescriptor.choice(
            FeatureId.ANC_LEVEL, VERIF_UNTESTED, FeatureDescriptor.SAFETY_NONE,
            1 << MODE_ANC, false, ANC_OPTIONS_1B);
    private static final FeatureDescriptor LOW_LATENCY_SPEC = FeatureDescriptor.toggle(
            FeatureId.LOW_LATENCY, VERIF_UNTESTED, false);

    private final EdifierProtocol.Decoder decoder = new EdifierProtocol.Decoder();
    private boolean v2, xor, preferredXor, d0Allowed;
    private int probeDialect;
    private volatile int batteryCmd = -1;        // 0=D0 单值, 1=F2 三段
    private int group;
    private volatile boolean noiseConfirmed;
    private volatile boolean lowLatencyConfirmed;
    private volatile FeatureDescriptor ancLevelSpec;
    private Runnable d0Timer, probeTimer;

    @Override protected String logTag() { return TAG; }
    @Override protected String unnamedDevice() { return "漫步者耳机"; }
    @Override protected long failureCooldown() { return 1500; }
    @Override protected String connectionFailure() { return "连不上漫步者控制通道(SPP/BLE 都没应答)"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{SPP_UUID}; }
    @Override protected int bleFragmentDelay() { return 30; }
    @Override protected boolean paceLastBleFragment() { return true; }
    @Override protected void openFallback() { openBle(null, BLE_NOTIFY, BLE_WRITE, 250, 5000); }
    @Override protected void prepare() {
        String name = deviceName().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_-]", "");
        preferredXor = !("edifierfitbudsturbo".equals(name) || "fitbudsturbo".equals(name));
        v2 = true;
        xor = preferredXor;
        noiseConfirmed = d0Allowed = lowLatencyConfirmed = false;
        batteryCmd = group = probeDialect = -1;
        ancLevelSpec = null;
        d0Timer = probeTimer = null;
        decoder.reset();
    }
    @Override protected void opened() { probe(0); }
    @Override protected void closed() {
        cancel(d0Timer);
        cancel(probeTimer);
        d0Timer = probeTimer = null;
    }

    private void probe(final int dialect) {
        cancel(d0Timer);
        cancel(probeTimer);
        d0Timer = probeTimer = null;
        if (dialect == 3) {
            fail("漫步者耳机没回应控制帧(可能型号/固件不支持)");
            return;
        }
        // 三种只读方言，明确的明文型号先试明文；先探 F2 避免 D0 抢答导致误锁定单电池。
        probeDialect = dialect;
        d0Allowed = false;
        v2 = dialect != 2;
        xor = dialect == 0 ? preferredXor : !preferredXor;
        sendFrame(CMD_BATT_F2, new byte[0]);
        sendAncQuery();
        final boolean expectV2 = v2;
        final boolean expectXor = xor;
        d0Timer = new Runnable() { @Override public void run() {
            if (!transportOpen() || batteryCmd == 1 || v2 != expectV2 || xor != expectXor) return;
            d0Allowed = true;
            sendFrame(CMD_BATT_D0, new byte[0]);
        }};
        schedule(d0Timer, 300);
        probeTimer = new Runnable() { @Override public void run() {
            if (probeDialect == dialect) probe(dialect + 1);
        }};
        schedule(probeTimer, 1500);
    }

    // ---- 发送 ----

    private void sendFrame(int cmd, byte[] data) {
        write(EdifierProtocol.encode(cmd, data, v2, xor));
    }

    // ---- 接收 ----

    @Override protected void received(byte[] data) {
        if (!transportOpen() || data == null || data.length == 0) return;
        boolean probing = probeDialect >= 0;
        for (EdifierProtocol.Frame frame : decoder.feed(data, data.length)) {
            if (frame.v2 != v2) continue;
            byte[] payload = frame.payload(xor);
            boolean recognized = false;
            int[] battery = EdifierProtocol.battery(frame.command, payload);
            if (battery != null) {
                // F2 优先；稍后到达的 D0 聚合电量不能把 TWS 切成单电池。
                if (frame.command == CMD_BATT_D0) d0Allowed = true;
                if (frame.command == CMD_BATT_F2 || batteryCmd != 1) {
                    batteryCmd = frame.command == CMD_BATT_F2 ? 1 : 0;
                    if (batteryCmd == 1) cancel(d0Timer);
                    confirmReady();
                    deliverBattery(battery[0], battery[1], battery[2], battery[3]);
                }
                recognized = true;
            } else if (frame.command == CMD_ANC_QUERY && (v2 ? payload.length >= 2 && payload.length <= 3 : payload.length >= 1 && payload.length <= 2)) {
                int returnedGroup = v2 ? payload[0] & 255 : 0;
                int value = payload[v2 ? 1 : 0] & 255;
                int mode = EdifierProtocol.mode(returnedGroup, value);
                boolean wind = EdifierProtocol.isWind(returnedGroup, value);
                if (mode >= 0 || wind) {
                    group = returnedGroup; noiseConfirmed = true; recognized = true;
                    confirmReady();
                    deliverMode(wind ? MODE_ANC : mode);
                    FeatureDescriptor levelSpec = v2 && returnedGroup == 0x10 ? ANC_SPEC_10
                            : v2 && returnedGroup == 0x1B ? ANC_SPEC_1B : null;
                    if (levelSpec != null) {
                        ancLevelSpec = levelSpec;
                        if (EdifierProtocol.isAncLevel(returnedGroup, value)) {
                            confirmFeature(levelSpec, value);
                        } else {
                            confirmFeature(levelSpec);
                        }
                    }
                }
            } else if (frame.command == CMD_GAME_QUERY && payload.length == 1
                    && (payload[0] == 0 || payload[0] == 1)) {
                lowLatencyConfirmed = true;
                recognized = true;
                confirmReady();
                confirmFeature(LOW_LATENCY_SPEC, payload[0] & 0xFF);
            } else if (frame.command == CMD_GAME_SET && lowLatencyConfirmed && payload.length == 1
                    && (payload[0] == 0 || payload[0] == 1)) {
                recognized = true;
                reportFeature(FeatureId.LOW_LATENCY, payload[0] & 0xFF);
            }
            if (!recognized) {
                trace(TAG, "rx unsupported cmd=" + Integer.toHexString(frame.command) + " payload=" + hex(payload));
            }
        }
        if (probing && isConnected()) {
            if (batteryCmd < 0) queryBattery();
            if (!noiseConfirmed) sendAncQuery();
        }
    }

    private void confirmReady() {
        if (probeDialect < 0) return;
        probeDialect = -1;
        cancel(probeTimer);
        ready();
    }

    private void sendAncQuery() {
        sendFrame(CMD_ANC_QUERY, new byte[0]);
    }

    private void deliverBattery(int l, int r, int c, int flags) {
        events().onBattery(l, r, c, flags);
    }

    private void deliverMode(int mode) {
        events().onNoiseMode(mode);
    }

    private static String hex(byte[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            int v = x & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v)).append(' ');
        }
        return sb.toString().trim();
    }

    @Override public synchronized void queryBattery() {
        if (!isConnected()) return;
        if (batteryCmd != 1) sendFrame(CMD_BATT_F2, new byte[0]);
        if (batteryCmd != 1 && d0Allowed) sendFrame(CMD_BATT_D0, new byte[0]);
    }

    @Override public synchronized void queryNoise() {
        if (isConnected()) sendAncQuery();
    }

    @Override public synchronized void changeNoise(int mode, Runnable flushed) {
        if (!isConnected()) return;
        byte[] target = noiseConfirmed ? EdifierProtocol.modeCommand(v2, group, mode) : null;
        if (target == null) { reportError("该耳机尚未确认降噪控制协议"); return; }
        writeThen(EdifierProtocol.encode(CMD_ANC_SET, target, v2, xor), () -> {
            if (flushed != null) flushed.run();
            schedule(this::sendAncQuery, 350);
        });
    }

    @Override public FeatureDescriptor[] featureDescriptors() {
        List<FeatureDescriptor> list = new ArrayList<>();
        if (ancLevelSpec != null) list.add(ancLevelSpec);
        if (isConnected()) list.add(LOW_LATENCY_SPEC);
        return list.toArray(new FeatureDescriptor[0]);
    }

    @Override public synchronized void queryFeature(FeatureId id) {
        if (!isConnected() || id == null) return;
        if (id == FeatureId.NOISE_MODE || id == FeatureId.ANC_LEVEL) {
            sendAncQuery();
        } else if (id == FeatureId.LOW_LATENCY) {
            sendFrame(CMD_GAME_QUERY, new byte[0]);
        }
    }

    @Override public synchronized void writeFeature(FeatureId id, int value, Runnable flushed) {
        if (!isConnected() || id == null) return;
        if (id == FeatureId.NOISE_MODE) {
            changeNoise(value, flushed);
        } else if (id == FeatureId.ANC_LEVEL) {
            FeatureDescriptor spec = ancLevelSpec;
            byte[] target = (noiseConfirmed && spec != null && spec.isValidValue(value))
                    ? EdifierProtocol.ancLevelCommand(v2, group, value) : null;
            if (target == null) return;
            writeThen(EdifierProtocol.encode(CMD_ANC_SET, target, v2, xor), () -> {
                if (flushed != null) flushed.run();
                schedule(this::sendAncQuery, 350);
            });
        } else if (id == FeatureId.LOW_LATENCY) {
            if (!lowLatencyConfirmed || (value != 0 && value != 1)) return;
            writeThen(EdifierProtocol.encode(CMD_GAME_SET, new byte[]{(byte) value}, v2, xor), () -> {
                if (flushed != null) flushed.run();
                schedule(() -> queryFeature(FeatureId.LOW_LATENCY), 350);
            });
        }
    }

    @Override public int caps() {
        // 头戴型号只有 D0 单值电量; TWS 是 F2 三段
        return (batteryCmd == 0 ? CAP_BATTERY_SINGLE : CAP_BATTERY) | (noiseConfirmed ? CAP_NOISE : 0);
    }

    @Override public int verifLevel() {
        return VERIF_UNTESTED;
    }

    @Override public String brandName() {
        return "漫步者";
    }
    @Override public int potentialCaps() { return caps() | CAP_NOISE; }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            if (name == null) return false;
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.matches("(?s).*(edifier|漫步者|neobuds|lollipods|fitbuds|fitclip|w820|w860|w260).*");
        }
        @Override public WireDriver create() { return new EdifierClient(); }
    }

}

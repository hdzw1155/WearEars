package com.hdz.wearcontrol;


import java.util.UUID;

/**
 * 漫步者(EDIFIER)客户端(实验性).
 *
 * 协议依据: HyperEars/docs/edifier-bes-protocol.md、mEDIFIER、BudsLink 三份开源实现 + 社区抓包.
 * V2 帧: AA | EC | CMD | LEN_H | LEN_L | PAYLOAD | CRC8 ; payload 默认 XOR 0xA5; CRC8=sum(前序线上字节)&FF
 * V1 帧: AA | LEN | CMD | DATA | CRC16_H | CRC16_L ; LEN=len(DATA)+1; CRC16=(0x2019+sum)&FFFF
 * 电量: D0 单值；F2 只发布已证实的左右耳字段，盒与充电标志保持未知。
 * 降噪: CC 查询拿 [group][mode] → C1 设置; 每个 group 的 mode 含义不同, 不猜 group.
 * 传输: SPP(EDF00000-...) 优先, 失败走 BLE GATT(特征 48090001/48090002, 按 UUID 找, 不强绑服务).
 * 只发 D0/F2/CC/C1, 其余命令一律不发.
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

    private final EdifierProtocol.Decoder decoder = new EdifierProtocol.Decoder();
    private boolean v2, xor, preferredXor;
    private int probeDialect;
    private volatile int batteryCmd = -1;        // 0=D0 单值, 1=F2 三段
    private int group;
    private volatile boolean noiseConfirmed;

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
        noiseConfirmed = false;
        batteryCmd = group = probeDialect = -1;
        decoder.reset();
    }
    @Override protected void opened() { probe(0); }

    private void probe(final int dialect) {
        if (dialect == 3) {
            fail("漫步者耳机没回应控制帧(可能型号/固件不支持)");
            return;
        }
        // 三种只读方言，明确的明文型号先试明文。
        probeDialect = dialect;
        v2 = dialect != 2;
        xor = dialect == 0 ? preferredXor : !preferredXor;
        sendFrame(CMD_BATT_F2, new byte[0]);
        sendFrame(CMD_BATT_D0, new byte[0]);
        sendAncQuery();
        schedule(new Runnable() { @Override public void run() {
            if (probeDialect == dialect) probe(dialect + 1);
        }}, 1500);
    }

    // ---- 发送 ----

    private void sendFrame(int cmd, byte[] data) {
        write(EdifierProtocol.encode(cmd, data, v2, xor));
    }

    // ---- 接收 ----

    @Override protected void received(byte[] data) {
        boolean probing = probeDialect >= 0;
        for (EdifierProtocol.Frame frame : decoder.feed(data, data.length)) {
            if (frame.v2 != v2) continue;
            byte[] payload = frame.payload(xor);
            boolean recognized = false;
            int[] battery = EdifierProtocol.battery(frame.command, payload);
            if (battery != null) {
                // F2 优先；稍后到达的 D0 聚合电量不能把 TWS 切成单电池。
                if (frame.command == CMD_BATT_F2 || batteryCmd != 1) {
                    batteryCmd = frame.command == CMD_BATT_F2 ? 1 : 0;
                    confirmReady();
                    deliverBattery(battery[0], battery[1], battery[2], battery[3]);
                }
                recognized = true;
            } else if (frame.command == CMD_ANC_QUERY && (v2 ? payload.length >= 2 && payload.length <= 3 : payload.length >= 1 && payload.length <= 2)) {
                int returnedGroup = v2 ? payload[0] & 255 : 0;
                int value = payload[v2 ? 1 : 0] & 255;
                int mode = EdifierProtocol.mode(returnedGroup, value);
                boolean wind = returnedGroup == 0x10 && value == 3 || returnedGroup == 0x1B && value == 4;
                if (mode >= 0 || wind) {
                    group = returnedGroup; noiseConfirmed = true; recognized = true;
                    confirmReady();
                    deliverMode(mode);
                }
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
        if (batteryCmd != 0) sendFrame(CMD_BATT_F2, new byte[0]);
        if (batteryCmd != 1) sendFrame(CMD_BATT_D0, new byte[0]);
    }

    @Override public synchronized void queryNoise() {
        if (isConnected()) sendAncQuery();
    }

    @Override public synchronized void changeNoise(int mode, Runnable flushed) {
        if (!isConnected()) return;
        byte[] target = noiseConfirmed ? EdifierProtocol.modeCommand(v2, group, mode) : null;
        if (target == null) { reportError("该耳机尚未确认降噪控制协议"); return; }
        writeThen(EdifierProtocol.encode(CMD_ANC_SET, target, v2, xor), () -> {
            flushed.run();
            schedule(this::sendAncQuery, 350);
        });
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
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.matches(".*(edifier|漫步者|neobuds|lollipods|fitbuds|fitclip|w820|w860|w260).*");
        }
        @Override public WireDriver create() { return new EdifierClient(); }
    }

}

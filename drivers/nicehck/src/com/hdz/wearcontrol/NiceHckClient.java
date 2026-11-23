package com.hdz.wearcontrol;


import java.util.UUID;

/**
 * 原道(NiceHCK / OriG in)客户端(实验性).
 *
 * 协议依据: ZaeXT/NiceHCK_Controller(Java, MIT) + HELPMEEADICE/NiceHCK_Controller_Windows(Rust), 两边常量一致.
 * 帧: 4E | len_lo len_hi | 00 | op_lo op_hi | params...
 *   len = 3 + params 长度(u16 小端, 只算 len 之后的字节, 含 00+op); 总长 = len + 3; 无校验.
 * 只做最小集: 电量(0x0005) + 降噪查询(0x0101)/设置(0x0201) + 100ms 后回读确认.
 * 不走 BLE, 纯 SPP; 通道号不写死, 交给系统 SDP(createRfcommSocketToServiceRecord).
 */
public class NiceHckClient extends WireDriver {

    private static final String TAG = "NiceHck";

    /** 尾部 ASCII "NHCKCTRL" */
    private static final UUID SPP_UUID = UUID.fromString("0000a100-1000-8000-4e48-434b4354524c");
    private static final UUID SPP_UUID_STD = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private static final int OP_BATTERY = 0x0005;
    private static final int OP_ANC_QUERY = 0x0101;
    private static final int OP_ANC_SET = 0x0201;

    private final NiceHckProtocol decoder = new NiceHckProtocol();
    private volatile boolean noiseConfirmed;
    private final Runnable confirm = new Runnable() {
        @Override public void run() {
            queryNoise();
            queryBattery();
        }
    };

    @Override protected String logTag() { return TAG; }
    @Override protected String unnamedDevice() { return "原道耳机"; }
    @Override protected long failureCooldown() { return 1500; }
    @Override protected String connectionFailure() { return "连不上原道控制通道(SPP 没开?)"; }
    @Override protected UUID[] services(UUID[] advertised) { return new UUID[]{SPP_UUID, SPP_UUID_STD}; }
    @Override protected void prepare() {
        noiseConfirmed = false;
        decoder.reset();
    }
    @Override protected void opened() {
        ready();
        queryBattery();
        schedule(new Runnable() { @Override public void run() { queryNoise(); }}, 120);
    }
    @Override protected void received(byte[] data) {
        for (byte[] frame : decoder.feed(data, data.length)) onFrame(frame, 0);
    }

    private void onFrame(byte[] b, int i) {
        int flen = (b[i + 1] & 0xFF) | ((b[i + 2] & 0xFF) << 8);
        int op = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8);
        int payloadLen = flen + 3 - 6;
        if (payloadLen < 0) return;
        try {
            if (op == OP_BATTERY && payloadLen >= 3) {
                int l = b[i + 6] & 0xFF;
                int r = b[i + 7] & 0xFF;
                int c = b[i + 8] & 0xFF;
                deliverBattery(l, r, c);
            } else if (op == OP_ANC_QUERY && payloadLen >= 1) {
                int wire = b[i + 6] & 0xFF;
                if (wire <= 3 || wire == 0x10 || wire == 0x11) {
                    noiseConfirmed = true;
                    deliverMode(mapMode(wire));
                }
            }
        } catch (RuntimeException e) {
            trace(TAG, "parse frame failed: " + e);
        }
    }

    /** 原道映射: 0=关 1=通透 2=普通降噪 3=深度降噪 0x10=试验性 0x11=风噪 */
    private static int mapMode(int v) {
        if (v == 0) return MODE_OFF;
        if (v == 1) return MODE_TRANS;
        if (v == 2 || v == 3) return MODE_ANC;
        return -1; // 试验/风噪/未知: 三态 UI 不点亮
    }

    private int toDeviceMode(int mode) {
        if (mode == MODE_OFF) return 0;
        if (mode == MODE_TRANS) return 1;
        if (mode == MODE_ANC) return 2;
        return -1;
    }

    private void sendFrame(int op, byte[] params) { write(frame(op, params)); }

    private static byte[] frame(int op, byte[] params) {
        int len = 3 + params.length;
        final byte[] f = new byte[len + 3];
        f[0] = 0x4E;
        f[1] = (byte) (len & 0xFF);
        f[2] = (byte) ((len >> 8) & 0xFF);
        f[3] = 0x00;
        f[4] = (byte) (op & 0xFF);
        f[5] = (byte) ((op >> 8) & 0xFF);
        if (params.length > 0) System.arraycopy(params, 0, f, 6, params.length);
        return f;
    }

    private void deliverBattery(int l, int r, int c) {
        // 0 = 未知/无, 原样交给渲染层。
        events().onBattery(l, r, c, 0);
    }

    private void deliverMode(int mode) {
        events().onNoiseMode(mode);
    }

    @Override public void queryBattery() {
        sendFrame(OP_BATTERY, new byte[0]);
    }

    @Override public void queryNoise() {
        sendFrame(OP_ANC_QUERY, new byte[0]);
    }

    @Override public void changeNoise(int mode, Runnable flushed) {
        int v = toDeviceMode(mode);
        if (!noiseConfirmed || v < 0) return;
        writeThen(frame(OP_ANC_SET, new byte[]{(byte) v, 0x00}), () -> {
            flushed.run();
            cancel(confirm);
            schedule(confirm, 150);
        });
    }

    @Override public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0);
    }

    @Override public int verifLevel() {
        // 原道 OriG in(原点) 已在本项目真机验证(电量 + 降噪/通透/关闭三态);
        // 其他原道型号沿用同一 4E 帧协议, 但未逐型号实测, 如实标实验性。
        return isOrigin(deviceName()) ? VERIF_REAL : VERIF_UNTESTED;
    }

    private static boolean isOrigin(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_-]", "");
        return n.matches("(?:nicehck|yuandao|原道)?(?:origin|原点)(?:原道原点)?");
    }

    @Override public String brandName() {
        return "原道";
    }
    @Override public int potentialCaps() { return caps() | CAP_NOISE; }

    /** 可选模块的唯一注册入口，匹配规则与协议实现一起增删。 */
    public static final class Module implements com.hdz.wearcontrol.DriverModule {
        @Override public boolean matches(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return n.contains("yuandao") || n.contains("nicehck") || n.contains("原道") || isOrigin(name);
        }
        @Override public WireDriver create() { return new NiceHckClient(); }
    }

}

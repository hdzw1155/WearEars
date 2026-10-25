package com.hdz.wearcontrol.vivo;

import com.hdz.wearcontrol.TwsClient;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * vivo TWS (Air3 Pro 实机验证) GAIA-over-RFCOMM 客户端。
 * 帧格式: FF | VERB | 00 | LEN | VENDOR(2,大端) | CMD(2,大端) | PAYLOAD(LEN)
 * 协议来源: HyperEars / TWS-Pods-PC 公开逆向文档。
 */
public class VivoGaiaClient implements TwsClient {

    public static final UUID GAIA_UUID =
            UUID.fromString("00000837-d102-11e1-9b23-00025b00a5a5");

    /** A2DP Sink, 用于兜底识别任意已配对耳机 */
    public static final UUID A2DP_SINK_UUID =
            UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb");

    public static final int VENDOR_QUALCOMM = 0x000A;
    public static final int VENDOR_VIVO = 0x001B;

    public static final int CMD_HANDSHAKE = 0x0300;
    public static final int CMD_NOISE_SET = 0x0130;
    public static final int CMD_NOISE_QUERY = 0x0230;
    public static final int CMD_BATT_QUERY = 0x0207;

    // 空间音频: 命令号来自反编译推断(TWS-Pods-PC),未官方证实,格式按开关类 [state] 模式
    public static final int CMD_SPATIAL_SET = 0x0139;
    public static final int CMD_SPATIAL_QUERY = 0x0239;
    public static final int RESP_SPATIAL_ACK = 0x8139;
    public static final int RESP_SPATIAL = 0x8239;

    // DeepX 音效: 命令号与格式已真机验证(Air3 Pro)——SET 单字节 [mode], ACK 0x8118 回 [00,mode],
    // 查询 0x8218 回 [00,mode]; mode 0=标准 1=清澈人声 2=超重低音(真机验证), 3=清亮高音 4=悠扬听书(按官方 App 显示顺序推断,未实测)
    public static final int CMD_DEEPX_SET = 0x0118;
    public static final int CMD_DEEPX_QUERY = 0x0218;
    public static final int RESP_DEEPX_ACK = 0x8118;
    public static final int RESP_DEEPX = 0x8218;

    // 空间音效预设(标准/剧场/音乐厅/录音棚/演唱会): 命令号来自官方 APK 反编译,未实测
    public static final int CMD_SPATIALFX_SET = 0x013E;
    public static final int CMD_SPATIALFX_QUERY = 0x023E;
    public static final int RESP_SPATIALFX_ACK = 0x813E;
    public static final int RESP_SPATIALFX = 0x823E;

    public static final int MODE_ANC = 0x00;
    public static final int MODE_OFF = 0x01;
    public static final int MODE_TRANS = 0x02;

    /** 机型线格式差异,参数来自 HyperEars VivoTwsProtocol.WireConfig */
    public static final int CFG_AIR3_PRO = 0;  // v3, 设置载荷 mode 04 00 (实机抓包)
    public static final int CFG_FAMILY_V4 = 1; // v4, mode 03 01 (家族默认画像)
    public static final int CFG_TWS_3E = 2;    // v3, mode 03 (TWS 3e 文档)

    /** 按蓝牙设备名猜机型线格式,未知名字用家族默认 */
    public static int cfgForName(String name) {
        if (name == null) return CFG_FAMILY_V4;
        String n = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (n.contains("air3")) return CFG_AIR3_PRO;
        if (n.contains("3e")) return CFG_TWS_3E;
        if (n.contains("tws3")) return CFG_TWS_3E; // TWS 3/3 Pro 与 3e 同代,推断同配置
        return CFG_FAMILY_V4;
    }

    public static final int RESP_NOISE_SET = 0x8130;
    public static final int RESP_NOISE_QUERY = 0x8230;
    public static final int RESP_BATT = 0x8207;

    public interface Listener extends TwsClient.Listener {
        void onConnected(String deviceName);

        void onDisconnected(String reason);

        void onError(String msg);

        void onBattery(int left, int right, int box, int chargeFlags);

        void onNoiseMode(int mode);

        /** 空间音频状态; null 表示耳机未确认/无法解析(指令为实验性) */
        void onSpatial(Boolean state);

        /** DeepX 音效模式(0=标准 1=清澈人声 2=超重低音, 3/4=推断值); null 表示无法解析/未确认 */
        void onDeepX(Integer mode);
    }

    private final List<TwsClient.Listener> listeners = new CopyOnWriteArrayList<TwsClient.Listener>();
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean closing = false;
    private int cfg = CFG_FAMILY_V4;

    public VivoGaiaClient() {
    }

    public VivoGaiaClient(Listener listener) {
        listeners.add(listener);
    }

    /** 支持多订阅者(页面 + 弹窗服务),回调发生在后台线程 */
    @Override
    public void addListener(TwsClient.Listener l) {
        listeners.add(l);
    }

    @Override
    public void removeListener(TwsClient.Listener l) {
        listeners.remove(l);
    }

    private void fireConnected(String name) {
        for (TwsClient.Listener l : listeners) l.onConnected(name);
    }

    private void fireDisconnected(String reason) {
        for (TwsClient.Listener l : listeners) l.onDisconnected(reason);
    }

    private void fireError(String msg) {
        for (TwsClient.Listener l : listeners) l.onError(msg);
    }

    private void fireBattery(int l, int r, int c, int f) {
        for (TwsClient.Listener li : listeners) li.onBattery(l, r, c, f);
    }

    private void fireMode(int mode) {
        for (TwsClient.Listener l : listeners) l.onNoiseMode(mode);
    }

    private void fireSpatial(Boolean state) {
        for (TwsClient.Listener l : listeners) {
            if (l instanceof Listener) ((Listener) l).onSpatial(state);
        }
    }

    private void fireDeepX(Integer mode) {
        for (TwsClient.Listener l : listeners) {
            if (l instanceof Listener) ((Listener) l).onDeepX(mode);
        }
    }

    /**
     * 空间音频应答解析(实验): 参照开关类 [..][state] 模式(0=关,非0=开),
     * 解析不了就返回 null,原始字节已在 logcat 里留档。
     */
    private static Boolean parseSpatial(byte[] p) {
        if (p == null || p.length == 0) return null;
        if (p.length == 1) return (p[0] & 0xFF) != 0;
        if ((p[0] & 0xFF) == 0) return (p[p.length - 1] & 0xFF) != 0;
        return null;
    }

    /**
     * DeepX 应答解析: 格式已真机验证为 [00, mode](ACK 0x8118 与查询 0x8218 均是),
     * payload[0]==0 且长度>=2 时取 payload[1] 为模式,解析不了返回 null,
     * 原始字节已在 logcat 里留档。
     */
    private static Integer parseDeepX(byte[] p) {
        if (p == null || p.length < 2) return null;
        if ((p[0] & 0xFF) == 0) return p[1] & 0xFF;
        return null;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    @Override
    public String brandName() {
        return "vivo";
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    /** 注意: Listener 回调发生在后台线程,UI 侧需自行切线程。 */
    public void connect(final BluetoothDevice device) {
        closing = false;
        cfg = cfgForName(device.getName());
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    try {
                        socket = device.createRfcommSocketToServiceRecord(GAIA_UUID);
                        socket.connect();
                    } catch (IOException e) {
                        try {
                            if (socket != null) socket.close();
                        } catch (IOException ignored) {
                        }
                        // SDP 找不到服务时退回固定 channel 13
                        socket = fallbackChannel(device, 13);
                        socket.connect();
                    }
                    out = socket.getOutputStream();
                } catch (Exception e) {
                    safeClose();
                    // 连不上先歇 2 秒再报错: 上一个进程被硬杀后,耳机侧连接位要几秒才释放,
                    // 立刻重试会陷入连-断循环(表现为"第二次进入卡死")
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                    if (closing) return; // 已被新的连接替代,不再回调
                    fireError("连接失败: " + e.getMessage()
                            + "\n(耳机未与手表配对? 或耳机已占满两个连接)");
                    return;
                }
                if (closing) {
                    safeClose();
                    return;
                }
                fireConnected(device.getName());
                sendHandshake();
                queryBattery();
                queryNoise();
                querySpatial();
                queryDeepX();
                startReader();
            }
        }, "gaia-connect").start();
    }

    private static BluetoothSocket fallbackChannel(BluetoothDevice device, int channel)
            throws Exception {
        // createRfcommSocket(int) 是隐藏 API,反射调用
        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
        return (BluetoothSocket) m.invoke(device, channel);
    }

    public void disconnect() {
        closing = true;
        safeClose();
    }

    private void safeClose() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- 发送 ----

    private synchronized void writeFrame(int verb, int vendor, int cmd, byte[] payload) {
        if (out == null) return;
        int len = payload == null ? 0 : payload.length;
        byte[] f = new byte[8 + len];
        f[0] = (byte) 0xFF;
        f[1] = (byte) verb;
        f[2] = 0x00;
        f[3] = (byte) len;
        f[4] = (byte) (vendor >> 8);
        f[5] = (byte) vendor;
        f[6] = (byte) (cmd >> 8);
        f[7] = (byte) cmd;
        if (payload != null && len > 0) System.arraycopy(payload, 0, f, 8, len);
        try {
            out.write(f);
            out.flush();
        } catch (IOException e) {
            fireError("发送失败: " + e.getMessage());
        }
    }

    public void sendHandshake() {
        writeFrame(0x04, VENDOR_QUALCOMM, CMD_HANDSHAKE, null);
    }

    public void queryBattery() {
        writeFrame(0x04, VENDOR_VIVO, CMD_BATT_QUERY, null);
    }

    public void queryNoise() {
        byte[] payload = cfg == CFG_FAMILY_V4 ? new byte[]{0x00} : new byte[0];
        writeFrame(cfg == CFG_FAMILY_V4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_NOISE_QUERY, payload);
    }

    public void setNoiseMode(int mode) {
        byte[] suffix;
        if (cfg == CFG_AIR3_PRO) suffix = new byte[]{0x04, 0x00};
        else if (cfg == CFG_TWS_3E) suffix = new byte[]{0x03};
        else suffix = new byte[]{0x03, 0x01};
        byte[] payload = new byte[1 + suffix.length];
        payload[0] = (byte) mode;
        System.arraycopy(suffix, 0, payload, 1, suffix.length);
        writeFrame(cfg == CFG_FAMILY_V4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_NOISE_SET, payload);
    }

    // ---- 空间音频(实验性) ----

    public void querySpatial() {
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_SPATIAL_QUERY,
                v4 ? new byte[]{0x00} : null);
    }

    /** 空间音频开关: v3/v4 均被耳机接受,取与降噪一致的 verb; 载荷 [01]=开 [00]=关
     *  (真机探测确认: Air3 Pro 上 v3 [01] 使 0x8239 状态翻转为 01) */
    public void setSpatial(boolean on) {
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_SPATIAL_SET,
                new byte[]{(byte) (on ? 0x01 : 0x00)});
    }

    // ---- DeepX 音效(真机验证) ----

    public void queryDeepX() {
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_DEEPX_QUERY,
                v4 ? new byte[]{0x00} : null);
    }

    /** DeepX 音效设置: 载荷为单字节 [mode],真实值 0/1/2/3/5(0x04 被官方跳过,悠扬听书=5),
     *  verb 按 cfg 取, ACK 0x8118 回 [00, mode],与降噪/空间音频同款套路 */
    public void setDeepX(int mode) {
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_DEEPX_SET,
                new byte[]{(byte) mode});
    }

    // ---- 空间音效预设(实验,APK 反编译推断) ----

    public void querySpatialFx() {
        boolean v4 = cfg == CFG_FAMILY_V4;
        writeFrame(v4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_SPATIALFX_QUERY,
                v4 ? new byte[]{0x00} : null);
    }

    /** 空间音效预设: payload 格式未知,探针轮换候选,每次调用发一种并留日志 */
    public void setSpatialFx(int preset) {
        final int[][] probes = {
                {0x03, preset},             // 0: v3 [preset]
                {0x03, preset, 0x00},       // 1: v3 [preset 00]
                {0x04, preset},             // 2: v4 [preset]
                {0x03, 0x00, preset},       // 3: v3 [00 preset]
                {0x04, 0x00, preset},       // 4: v4 [00 preset]
        };
        int i = spatialFxProbeIdx++ % probes.length;
        int verb = probes[i][0];
        byte[] payload = new byte[probes[i].length - 1];
        for (int k = 1; k < probes[i].length; k++) payload[k - 1] = (byte) probes[i][k];
        android.util.Log.d("GaiaClient", "sfx probe#" + i + " verb=0x"
                + Integer.toHexString(verb) + " payload=" + toHex(payload));
        writeFrame(verb, VENDOR_VIVO, CMD_SPATIALFX_SET, payload);
    }

    private static int spatialFxProbeIdx = 0;

    // ---- 接收 ----

    private void startReader() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = socket.getInputStream();
                    byte[] head = new byte[8];
                    while (!closing) {
                        if (!readFully(in, head, 8)) break;
                        if ((head[0] & 0xFF) != 0xFF) {
                            continue; // 流错位,丢弃继续同步
                        }
                        int len = head[3] & 0xFF;
                        int vendor = ((head[4] & 0xFF) << 8) | (head[5] & 0xFF);
                        int cmd = ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
                        byte[] payload = new byte[len];
                        if (len > 0 && !readFully(in, payload, len)) break;

                        if (vendor == VENDOR_VIVO && cmd == RESP_BATT && payload.length >= 5) {
                            fireBattery(payload[1] & 0xFF, payload[2] & 0xFF,
                                    payload[3] & 0xFF, payload[4] & 0xFF);
                        } else if (vendor == VENDOR_VIVO
                                && (cmd == RESP_NOISE_QUERY || cmd == RESP_NOISE_SET)
                                && payload.length >= 2) {
                            fireMode(payload[1] & 0xFF);
                        } else if (vendor == VENDOR_VIVO
                                && (cmd == RESP_SPATIAL || cmd == RESP_SPATIAL_ACK)) {
                            android.util.Log.d("GaiaClient",
                                    "spatial cmd=0x" + Integer.toHexString(cmd)
                                            + " payload=" + toHex(payload));
                            fireSpatial(parseSpatial(payload));
                        } else if (vendor == VENDOR_VIVO
                                && (cmd == RESP_DEEPX || cmd == RESP_DEEPX_ACK)) {
                            android.util.Log.d("GaiaClient",
                                    "deepx cmd=0x" + Integer.toHexString(cmd)
                                            + " payload=" + toHex(payload));
                            fireDeepX(parseDeepX(payload));
                        } else if (vendor == VENDOR_VIVO) {
                            // 未知帧全量记录,留作协议分析 ground truth
                            android.util.Log.d("GaiaClient",
                                    "unknown cmd=0x" + Integer.toHexString(cmd)
                                            + " payload=" + toHex(payload));
                        }
                    }
                    if (!closing) fireDisconnected("连接已关闭");
                } catch (IOException e) {
                    if (!closing) fireDisconnected("连接断开: " + e.getMessage());
                }
                // 断开后真正关闭 socket,让 isConnected() 反映真实状态
                safeClose();
            }
        }, "gaia-reader").start();
    }

    private static boolean readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }
}

package com.hdz.wearcontrol.huawei;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hdz.wearcontrol.TwsClient;
import com.hdz.wearcontrol.ProtocolWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
 *   mode: 00 关 / 01 降噪 / 02 通透; level: mode=0 用 00, 否则 FF。
 *   group 2B cmd 2A 读, 参数 TLV1/02; 应答 TLV1 = [level, mode](mode 在第二字节)。
 *   写没有回执, 400ms 后补一次 2A 读回确认; 耳机按键改档会主动发 2B 03。
 *
 * SE4 的 SPP 监听在信道 1(旧款 5i/4i 在 16), SDP 找不到时反射 createRfcommSocket 兜底。
 */
public class HuaweiSppClient implements TwsClient {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int GROUP_BATTERY = 0x01;
    private static final int GROUP_ANC = 0x2B;

    private static final int CMD_BATTERY_QUERY = 0x08;
    private static final int CMD_BATTERY_REPORT = 0x27;
    private static final int CMD_ANC_SWITCH = 0x04;
    private static final int CMD_ANC_QUERY = 0x2A;
    private static final int CMD_ANC_REPORT = 0x03;

    private static final int CHANNEL_SE4 = 1;
    private static final int CHANNEL_LEGACY = 16;

    private static final int MAX_FRAME = 1024;

    private static final byte[] BATTERY_QUERY_PARAMS = {
            0x01, 0x00, 0x02, 0x00, 0x03, 0x00
    };
    private static final byte[] ANC_QUERY_PARAMS = {
            0x01, 0x00, 0x02, 0x00
    };

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ProtocolWriter writer = new ProtocolWriter();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closing = false;
    private volatile boolean noiseConfirmed;
    /** 当前连接设备名, 用于按型号如实标注验证等级 */
    private volatile String connectedName = "";

    @Override
    public void init(Context ctx) {
        // SPP 协议自包含, 不需要系统 Profile 代理
    }

    @Override
    public String brandName() {
        return "Huawei";
    }

    @Override
    public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0);
    }

    @Override
    public int verifLevel() {
        // FreeBuds 6i 已在本项目真机验证(电量 + 降噪/通透/关闭三态);
        // 其他华为/荣耀型号沿用同一 SPP 协议族, 但未逐型号实测, 如实标实验性。
        String n = connectedName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.equals("huaweifreebuds6i") || n.equals("freebuds6i")) {
            return VERIF_REAL;
        }
        return VERIF_UNTESTED;
    }

    // ---- 华为协议没有实验能力命令 ----

    @Override
    public void querySpatial() {
    }

    @Override
    public void setSpatial(boolean on) {
    }

    @Override
    public void queryDeepX() {
    }

    @Override
    public void setDeepX(int mode) {
    }

    @Override
    public void addListener(Listener l) {
        listeners.add(l);
    }

    @Override
    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    @Override
    public boolean isConnected() {
        BluetoothSocket s = socket;
        return s != null && s.isConnected();
    }

    // ---- 连接 ----

    @Override
    public void connect(final BluetoothDevice device) {
        disconnect();
        writer.start();
        closing = false;
        noiseConfirmed = false;
        connectedName = device != null && device.getName() != null ? device.getName() : "";
        new Thread(new Runnable() {
            @Override public void run() {
                BluetoothSocket s = null;
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        try {
                            adapter.cancelDiscovery();
                        } catch (Exception ignored) {
                        }
                    }
                    s = openSocket(device);
                    if (s == null) throw new IOException("找不到 SPP 服务(信道 1/16)");
                    socket = s;
                    out = s.getOutputStream();
                } catch (Exception e) {
                    closeQuietly(s);
                    safeClose();
                    if (closing) return;
                    for (Listener l : listeners) {
                        l.onError("连接失败: " + e.getMessage() + "\n(耳机需先与手表配对)");
                    }
                    return;
                }
                if (closing) {
                    safeClose();
                    return;
                }
                for (Listener l : listeners) l.onConnected(device.getName());
                startReader();
                // 初始化: 电量 + 降噪状态
                queryBattery();
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        queryNoise();
                    }
                }, 250);
            }
        }, "hw-connect").start();
    }

    /** 先 SDP, 再反射固定信道(SE4=1, 旧款=16) */
    private BluetoothSocket openSocket(BluetoothDevice device) {
        BluetoothSocket s = null;
        try {
            s = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket = s;
            if (closing) { closeQuietly(s); return null; }
            s.connect();
            if (closing) { closeQuietly(s); return null; }
            return s;
        } catch (Exception e) {
            closeQuietly(s);
            Log.d("HwClient", "SDP connect fail: " + e);
        }
        int[] channels = {CHANNEL_SE4, CHANNEL_LEGACY};
        for (int ch : channels) {
            if (closing) return null;
            try {
                s = channelSocket(device, ch);
                socket = s;
                if (closing) { closeQuietly(s); return null; }
                s.connect();
                if (closing) { closeQuietly(s); return null; }
                Log.d("HwClient", "connected via channel " + ch);
                return s;
            } catch (Exception e) {
                closeQuietly(s);
                Log.d("HwClient", "channel " + ch + " fail: " + e);
            }
        }
        return null;
    }

    private static BluetoothSocket channelSocket(BluetoothDevice device, int channel)
            throws Exception {
        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
        return (BluetoothSocket) m.invoke(device, channel);
    }

    @Override
    public void disconnect() {
        closing = true;
        writer.stop();
        main.removeCallbacksAndMessages(null);
        safeClose();
    }

    private void safeClose() {
        try {
            BluetoothSocket s = socket;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private void fireError(String msg) {
        if (closing) return;
        for (Listener l : listeners) l.onError(msg);
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
    public void setNoiseMode(int mode) {
        if (mode != MODE_ANC && mode != MODE_OFF && mode != MODE_TRANS) return;
        int wire;
        if (mode == MODE_ANC) wire = 0x01;
        else if (mode == MODE_TRANS) wire = 0x02;
        else wire = 0x00;
        byte level = (byte) (wire == 0x00 ? 0x00 : 0xFF);
        byte[] params = {0x01, 0x02, (byte) wire, level};
        writeFrame(GROUP_ANC, CMD_ANC_SWITCH, params);
        // 写命令没有回执, 补一次读回确认
        main.postDelayed(new Runnable() {
            @Override public void run() {
                queryNoise();
            }
        }, 400);
    }

    /** 组帧并发送: 5A len(2B BE) 00 group cmd params CRC16(大端) */
    private synchronized void writeFrame(final int group, final int cmd, byte[] params) {
        final OutputStream o = out;
        if (closing || o == null) return;
        int plen = params == null ? 0 : params.length;
        int len = plen + 3;
        final byte[] f = new byte[6 + plen + 2];
        f[0] = (byte) 0x5A;
        f[1] = (byte) (len >> 8);
        f[2] = (byte) len;
        f[3] = (byte) 0x00;
        f[4] = (byte) group;
        f[5] = (byte) cmd;
        if (plen > 0) System.arraycopy(params, 0, f, 6, plen);
        int crc = crc16Xmodem(f, 0, 6 + plen);
        f[6 + plen] = (byte) (crc >> 8);
        f[7 + plen] = (byte) crc;
        if (!writer.post(new Runnable() {
            @Override public void run() {
                if (closing || o != out) return;
                try {
                    o.write(f); o.flush();
                    Log.d("HwClient", "tx g=0x" + Integer.toHexString(group)
                            + " c=0x" + Integer.toHexString(cmd) + " " + toHex(f));
                } catch (IOException e) { if (!closing && o == out) fireError("发送失败: " + e.getMessage()); }
            }
        }) && !closing) fireError("控制指令过多，请稍后重试");
    }

    /** CRC16/XMODEM: poly=0x1021, init=0, 无反射, 无最终 XOR */
    static int crc16Xmodem(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                else crc = (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    // ---- 接收 ----

    private void startReader() {
        final BluetoothSocket source = socket;
        if (closing || source == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] stage = new byte[2048];
                int stageLen = 0;
                byte[] chunk = new byte[512];
                try {
                    InputStream in = source.getInputStream();
                    while (!closing && socket == source) {
                        int r = in.read(chunk);
                        if (closing || socket != source) break;
                        if (r < 0) break;
                        if (stageLen + r > stage.length) {
                            stageLen = 0; // 防异常数据撑爆
                            continue;
                        }
                        System.arraycopy(chunk, 0, stage, stageLen, r);
                        stageLen += r;

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
                            if (len < 3 || total > MAX_FRAME) {
                                off = head + 1;
                                continue;
                            }
                            if (head + total > stageLen) {
                                off = head;
                                break;
                            }
                            int expect = ((stage[head + total - 2] & 0xFF) << 8)
                                    | (stage[head + total - 1] & 0xFF);
                            boolean ok = (stage[head + 3] & 0xFF) == 0x00
                                    && crc16Xmodem(stage, head, total - 2) == expect;
                            if (ok) {
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
                    if (!closing && socket == source) {
                        for (Listener l : listeners) l.onDisconnected("连接已关闭");
                    }
                } catch (IOException e) {
                    if (!closing && socket == source) {
                        for (Listener l : listeners) l.onDisconnected("连接断开: " + e.getMessage());
                    }
                }
                if (socket == source) safeClose();
            }
        }, "hw-reader").start();
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
        Log.d("HwClient", "rx g=0x" + Integer.toHexString(group)
                + " c=0x" + Integer.toHexString(cmd) + " [" + sb.toString().trim() + "]");

        if (group == GROUP_BATTERY
                && (cmd == CMD_BATTERY_QUERY || cmd == CMD_BATTERY_REPORT)) {
            parseBattery(b, paramsOff, paramsLen);
        } else if (group == GROUP_ANC && cmd == CMD_ANC_QUERY) {
            parseAncState(b, paramsOff, paramsLen);
        } else if (group == GROUP_ANC && cmd == CMD_ANC_REPORT) {
            // 耳机按键改了档位, 重新查询一次
            queryNoise();
        }
    }

    /** 电量 TLV: 01 整机电量 / 02 左右仓电量 / 03 充电位(整机级) */
    private void parseBattery(byte[] b, int off, int len) {
        int end = off + len;
        int p = off;
        int overall = -1;
        int l = 0xFF, r = 0xFF, c = 0xFF;
        int flags = 0;
        while (p + 2 <= end) {
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) break;
            if (type == 1 && tlen >= 1) {
                overall = b[v] & 0xFF;
            } else if (type == 2 && tlen >= 3) {
                l = b[v] & 0xFF;
                r = b[v + 1] & 0xFF;
                c = b[v + 2] & 0xFF;
            } else if (type == 3) {
                for (int i = 0; i < tlen; i++) {
                    if ((b[v + i] & 0x01) != 0) {
                        flags = 1 | 2 | 4;
                        break;
                    }
                }
            }
            p = v + tlen;
        }
        Log.d("HwClient", "battery overall=" + overall + " L/R/C=" + l + "/" + r + "/" + c
                + " flags=" + flags);
        for (Listener li : listeners) {
            li.onBattery(normPct(l), normPct(r), normPct(c), flags);
        }
    }

    /** ANC 读回应答: TLV1 = [level, mode], mode 在第二字节 */
    private void parseAncState(byte[] b, int off, int len) {
        int end = off + len;
        int p = off;
        while (p + 2 <= end) {
            int type = b[p] & 0xFF;
            int tlen = b[p + 1] & 0xFF;
            int v = p + 2;
            if (v + tlen > end) break;
            if (type == 1 && tlen >= 2) {
                int mode = b[v + 1] & 0xFF;
                int ui;
                if (mode == 0x01) ui = MODE_ANC;
                else if (mode == 0x02) ui = MODE_TRANS;
                else if (mode == 0x00) ui = MODE_OFF;
                else ui = -1;
                if (ui >= 0) noiseConfirmed = true;
                Log.d("HwClient", "anc level=" + (b[v] & 0xFF) + " mode=" + mode + " ui=" + ui);
                for (Listener li : listeners) li.onNoiseMode(ui);
                return;
            }
            p = v + tlen;
        }
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
}

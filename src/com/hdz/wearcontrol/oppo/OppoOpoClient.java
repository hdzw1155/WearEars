package com.hdz.wearcontrol.oppo;

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
public class OppoOpoClient implements TwsClient {

    private static final UUID UUID_OPPO =
            UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5");
    private static final UUID UUID_FALLBACK =
            UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5");
    private static final int RFCOMM_CHANNEL = 15;

    private static final int CMD_HELLO = 0x0100;
    private static final int CMD_PRODUCT = 0x0103;
    private static final int CMD_BATTERY_QUERY = 0x0106;
    private static final int CMD_ANC_QUERY = 0x010C;
    private static final int CMD_WEAR_QUERY = 0x0109;
    private static final int CMD_ANC_SET = 0x0404;
    private static final int CMD_CAPS = 0x0200;
    private static final int CMD_SUBSCRIBE = 0x0205;
    private static final int CMD_010D = 0x010D;
    private static final int CMD_0108 = 0x0108;
    private static final int CMD_0105 = 0x0105;

    private static final int RSP_BATTERY = 0x8106;
    private static final int RSP_ANC = 0x810C;
    private static final int RSP_ANC_ACK = 0x8404;
    private static final int RSP_HELLO = 0x8100;
    private static final int RSP_PRODUCT = 0x8103;
    private static final int RSP_WEAR = 0x8109;
    private static final int CMD_EVENT = 0x0204;

    private static final int MASK_ANC_SET_OFF = 0x01;
    private static final int MASK_ANC_SET_ANC = 0x02;
    private static final int MASK_ANC_SET_TRANS = 0x04;


    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ProtocolWriter writer = new ProtocolWriter();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closing = false;
    private volatile boolean ready = false;
    private volatile boolean noiseConfirmed;
    private volatile String connectedName = "";
    private int seq = 1;
    private int pendingMode = -1;

    @Override
    public void init(Context ctx) {
    }

    @Override
    public String brandName() {
        return "OPPO";
    }

    @Override
    public int caps() {
        return CAP_BATTERY | (noiseConfirmed ? CAP_NOISE : 0);
    }

    @Override
    public int verifLevel() {
        return OppoProtocol.isVerifiedReal(connectedName) ? VERIF_REAL : VERIF_UNTESTED;
    }

    // ---- OPPO 协议没有实验能力命令 ----

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
        ready = false;
        noiseConfirmed = false;
        connectedName = device != null && device.getName() != null ? device.getName() : "";
        pendingMode = -1;
        seq = 1;
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
                    if (s == null) throw new IOException("找不到 OPPO 控制服务(信道 15)");
                    socket = s;
                    out = s.getOutputStream();
                } catch (Exception e) {
                    closeQuietly(s);
                    safeClose();
                    if (closing) return;
                    fireError("连接失败: " + e.getMessage() + "\n(耳机需先与手表配对)");
                    return;
                }
                if (closing) {
                    safeClose();
                    return;
                }
                for (Listener l : listeners) l.onConnected(device.getName());
                startReader();
                runInit();
            }
        }, "oppo-connect").start();
    }

    private BluetoothSocket openSocket(BluetoothDevice device) {
        BluetoothSocket s = null;
        UUID[] uuids = {UUID_OPPO, UUID_FALLBACK};
        for (UUID u : uuids) {
            if (closing) return null;
            try {
                s = device.createRfcommSocketToServiceRecord(u);
                socket = s;
                if (closing) { closeQuietly(s); return null; }
                s.connect();
                if (closing) { closeQuietly(s); return null; }
                Log.d("OppoClient", "connected via " + u);
                return s;
            } catch (Exception e) {
                closeQuietly(s);
                Log.d("OppoClient", "secure " + u + " fail: " + e);
            }
            try {
                s = device.createInsecureRfcommSocketToServiceRecord(u);
                socket = s;
                if (closing) { closeQuietly(s); return null; }
                s.connect();
                if (closing) { closeQuietly(s); return null; }
                Log.d("OppoClient", "connected insecure via " + u);
                return s;
            } catch (Exception e) {
                closeQuietly(s);
                Log.d("OppoClient", "insecure " + u + " fail: " + e);
            }
        }
        try {
            s = channelSocket(device, RFCOMM_CHANNEL);
            socket = s;
            if (closing) { closeQuietly(s); return null; }
            s.connect();
            if (closing) { closeQuietly(s); return null; }
            Log.d("OppoClient", "connected via channel " + RFCOMM_CHANNEL);
            return s;
        } catch (Exception e) {
            closeQuietly(s);
            Log.d("OppoClient", "channel " + RFCOMM_CHANNEL + " fail: " + e);
        }
        return null;
    }

    private static BluetoothSocket channelSocket(BluetoothDevice device, int channel)
            throws Exception {
        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
        return (BluetoothSocket) m.invoke(device, channel);
    }

    /** 握手初始化序列(在连接线程里按节奏发, 不阻塞 UI) */
    private void runInit() {
        try {
            Thread.sleep(300);
            writeFrame(CMD_HELLO, nextSeq(), null);
            sendSimple(CMD_PRODUCT, new byte[0]);
            sendSimple(CMD_CAPS, new byte[0]);
            sendSimple(CMD_SUBSCRIBE, new byte[]{0x03, 0x01, 0x02, 0x03});
            sendSimple(CMD_010D, new byte[0]);
            sendSimple(CMD_ANC_QUERY, new byte[]{0x01, 0x01});
            sendSimple(CMD_BATTERY_QUERY, new byte[0]);
            sendSimple(CMD_WEAR_QUERY, new byte[0]);
            sendSimple(CMD_0108, new byte[0]);
            sendSimple(CMD_0105, new byte[0]);
        } catch (InterruptedException ignored) {
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                markReady();
            }
        }, 400);
    }

    private void sendSimple(int cmd, byte[] payload) throws InterruptedException {
        Thread.sleep(200);
        writeFrame(cmd, nextSeq(), payload);
    }

    private synchronized int nextSeq() {
        int s = seq & 0xFF;
        seq = (seq + 1) & 0xFF;
        if (seq == 0) seq = 1;
        return s;
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
        ready = false;
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

    private void markReady() {
        if (closing || ready) return;
        ready = true;
        Log.d("OppoClient", "init done, ready");
        queryBattery();
        queryNoise();
        int mode = pendingMode;
        pendingMode = -1;
        if (mode >= 0) setNoiseMode(mode);
    }

    // ---- 命令 ----

    @Override
    public void queryBattery() {
        if (!ready) return;
        writeFrame(CMD_BATTERY_QUERY, 0xF0, null);
    }

    @Override
    public void queryNoise() {
        if (!ready) return;
        writeFrame(CMD_ANC_QUERY, 0xF1, new byte[]{0x01, 0x01});
    }

    @Override
    public void setNoiseMode(int mode) {
        if (mode != MODE_ANC && mode != MODE_OFF && mode != MODE_TRANS) return;
        if (!ready) {
            pendingMode = mode;
            return;
        }
        int mask;
        if (mode == MODE_ANC) mask = MASK_ANC_SET_ANC;
        else if (mode == MODE_TRANS) mask = MASK_ANC_SET_TRANS;
        else mask = MASK_ANC_SET_OFF;
        writeFrame(CMD_ANC_SET, 0xF3, new byte[]{0x01, 0x01, (byte) mask});
        main.postDelayed(new Runnable() {
            @Override public void run() {
                queryNoise();
            }
        }, 350);
    }

    /** 组帧: AA TotalLen(LEB128) 00 00 cmd(2B LE) seq payLen(2B LE) payload */
    private synchronized void writeFrame(int cmd, int seqNo, byte[] payload) {
        final OutputStream o = out;
        if (closing || o == null) return;
        int payLen = payload == null ? 0 : payload.length;
        int totalLen = 7 + payLen;
        int lebLen = totalLen < 0x80 ? 1 : 2;
        final byte[] f = new byte[1 + lebLen + 7 + payLen];
        int p = 0;
        f[p++] = (byte) 0xAA;
        if (lebLen == 1) {
            f[p++] = (byte) totalLen;
        } else {
            f[p++] = (byte) ((totalLen & 0x7F) | 0x80);
            f[p++] = (byte) ((totalLen >> 7) & 0x7F);
        }
        f[p++] = 0x00;                       // slice
        f[p++] = 0x00;
        f[p++] = (byte) (cmd & 0xFF);        // cmd LE
        f[p++] = (byte) ((cmd >> 8) & 0xFF);
        f[p++] = (byte) seqNo;
        f[p++] = (byte) (payLen & 0xFF);     // payLen LE
        f[p++] = (byte) ((payLen >> 8) & 0xFF);
        if (payLen > 0) System.arraycopy(payload, 0, f, p, payLen);
        if (!writer.post(new Runnable() {
            @Override public void run() {
                if (closing || o != out) return;
                try {
                    o.write(f); o.flush();
                    Log.d("OppoClient", "tx " + toHex(f));
                } catch (IOException e) { if (!closing && o == out) fireError("发送失败: " + e.getMessage()); }
            }
        }) && !closing) fireError("控制指令过多，请稍后重试");
    }

    // ---- 接收 ----

    private void startReader() {
        final BluetoothSocket source = socket;
        if (closing || source == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                OppoProtocol.Decoder decoder = new OppoProtocol.Decoder();
                byte[] chunk = new byte[512];
                try {
                    InputStream in = source.getInputStream();
                    while (!closing && socket == source) {
                        int count = in.read(chunk);
                        if (closing || socket != source) break;
                        if (count < 0) break;
                        for (OppoProtocol.Frame frame : decoder.feed(chunk, count)) {
                            if (closing) break;
                            dispatch(frame);
                        }
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
        }, "oppo-reader").start();
    }

    private void dispatch(OppoProtocol.Frame frame) {
        int cmd = frame.command;
        int seqNo = frame.sequence;
        byte[] payload = frame.payload;
        Log.d("OppoClient", "rx cmd=0x" + Integer.toHexString(cmd)
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
            Log.d("OppoClient", "hello caps " + toHex(payload));
        } else if (cmd == RSP_PRODUCT) {
            Log.d("OppoClient", "product id " + toHex(payload));
        } else if (cmd == RSP_WEAR) {
            Log.d("OppoClient", "wear " + toHex(payload));
        }
    }

    private void parseBattery(byte[] payload, boolean event) {
        int[] value = OppoProtocol.battery(payload, event);
        if (value == null) return;
        for (Listener li : listeners) li.onBattery(value[0], value[1], value[2], value[3]);
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
            Log.d("OppoClient", "wear event " + toHex(payload));
        }
    }

    private void parseAncState(byte[] payload) {
        int mask = OppoProtocol.noiseMask(payload);
        if (mask >= 0) fireAncMask(mask);
    }

    private void fireAncMask(int mask) {
        int ui = OppoProtocol.noiseMode(mask);
        if (ui < 0) return;
        noiseConfirmed = true;
        Log.d("OppoClient", "anc mask=0x" + Integer.toHexString(mask) + " ui=" + ui);
        for (Listener li : listeners) li.onNoiseMode(ui);
    }

    private void parseAncAck(byte[] payload) {
        if (payload.length >= 1) {
            int status = payload[0] & 0xFF;
            if (status != 0x00) {
                Log.d("OppoClient", "anc set ack status=0x" + Integer.toHexString(status));
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
}

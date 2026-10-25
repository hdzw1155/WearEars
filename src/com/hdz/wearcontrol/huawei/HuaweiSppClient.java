package com.hdz.wearcontrol.huawei;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.hdz.wearcontrol.TwsClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 华为 FreeBuds / HONOR 的 SPP 协议客户端(RFCOMM)。
 * 协议来源: TWS-Pods-PC huawei 目录(HuaweiPods 逆向文档)。
 * 帧格式: 5A 00 06 00 [group] [cmd] [p1] [p2] [value] [crc16/xmodem 2B]。
 * 降噪: group 0x2B, 开关 cmd 0x04; 通透模式协议未见文档, 暂不支持。
 * 电量走 HFP AT 通道(+HUAWEIBATTERY=): 优先经系统的蓝牙 Headset 服务发 AT,
 * SPP 上的裸 AT 询问保留为兜底(多数机型无回包)。
 */
public class HuaweiSppClient implements TwsClient {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int GROUP_ANC = 0x2B;
    private static final int CMD_ANC_SWITCH = 0x04;

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closing = false;
    // HFP 电量通道: 系统的 Headset 代理(需手表已连耳机的 HFP)
    private android.bluetooth.BluetoothAdapter hfAdapter;
    private BluetoothHeadset headset;
    private Context appCtx;
    private final android.content.BroadcastReceiver atReceiver =
            new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context ctx, android.content.Intent i) {
                    // 耳机经 HFP 主动上报的 AT 行(含电量)
                    if (BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT.equals(
                            i.getAction())) {
                        String cmd = i.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS);
                        if (cmd != null) handleLine(cmd);
                    }
                }
            };

    /** TwsClient.init: 记下上下文和 Headset 代理, 用于走 HFP 通道问电量 */
    @Override
    public void init(Context ctx) {
        if (ctx == null) return;
        appCtx = ctx.getApplicationContext();
        try {
            hfAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            hfAdapter.getProfileProxy(appCtx, new android.bluetooth.BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                    headset = (BluetoothHeadset) proxy;
                }

                @Override public void onServiceDisconnected(int profile) {
                    headset = null;
                }
            }, android.bluetooth.BluetoothProfile.HEADSET);
            android.content.IntentFilter f = new android.content.IntentFilter(
                    BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
            appCtx.registerReceiver(atReceiver, f);
        } catch (Exception e) {
            Log.d("HwClient", "headset proxy fail: " + e);
        }
    }

    private void teardownHfp() {
        if (appCtx == null) return;
        try {
            appCtx.unregisterReceiver(atReceiver);
        } catch (Exception ignored) {
        }
        if (headset != null && hfAdapter != null) {
            try {
                hfAdapter.closeProfileProxy(android.bluetooth.BluetoothProfile.HEADSET, headset);
            } catch (Exception ignored) {
            }
            headset = null;
        }
    }

    @Override
    public String brandName() {
        return "Huawei";
    }

    @Override
    public int caps() {
        return CAP_NOISE | CAP_BATTERY;
    }

    /** SPP 帧未在真机验证; HFP 电量通道也待真机确认 */
    @Override
    public int verifLevel() {
        return VERIF_UNTESTED;
    }

    // ---- 实验能力: 华为协议无这些命令, 空实现 ----

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
        return socket != null && socket.isConnected();
    }

    @Override
    public void connect(final BluetoothDevice device) {
        closing = false;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                    out = socket.getOutputStream();
                } catch (Exception e) {
                    safeClose();
                    if (closing) return; // 已被新连接替代,不再打扰 UI
                    for (Listener l : listeners)
                        l.onError("连接失败: " + e.getMessage() + "\n(耳机需先与手表配对)");
                    return;
                }
                if (closing) {
                    safeClose();
                    return;
                }
                for (Listener l : listeners) l.onConnected(device.getName());
                queryBattery(); // 尽力而为的 AT 询问
                startReader();
            }
        }, "hw-connect").start();
    }

    @Override
    public void disconnect() {
        closing = true;
        teardownHfp();
        safeClose();
    }

    private void safeClose() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- 命令 ----

    @Override
    public void queryBattery() {
        // 电量在 HFP AT 通道: 优先走系统 Headset 代理对已连的 HFP 设备发 AT,
        // 手表若只配对未连 HFP 则退回 SPP 裸询问(多数机型无回包)
        boolean sent = false;
        if (headset != null) {
            try {
                for (BluetoothDevice d : headset.getConnectedDevices()) {
                    if (headset.sendVendorSpecificResultCode(d,
                            "AT+HUAWEIBATTERY?", "+HUAWEIBATTERY")) {
                        sent = true;
                        Log.d("HwClient", "battery asked via HFP to " + d.getName());
                    }
                }
            } catch (Exception e) {
                Log.d("HwClient", "HFP ask fail: " + e);
            }
        }
        if (!sent) writeAscii("AT+HUAWEIBATTERY?\r\n");
    }

    @Override
    public void queryNoise() {
        // 协议文档中未见降噪状态查询命令, 不发
    }

    @Override
    public void setNoiseMode(int mode) {
        if (mode == MODE_TRANS) {
            for (Listener l : listeners)
                l.onError("华为机型暂不支持通透切换(协议未公开)");
            return;
        }
        writeFrame(GROUP_ANC, CMD_ANC_SWITCH, 0x01, 0x01, mode == MODE_ANC ? 0x01 : 0x00);
    }

    /** 5A 00 06 00 group cmd p1 p2 value + CRC16/XMODEM(对前 9 字节) */
    private synchronized void writeFrame(int group, int cmd, int p1, int p2, int value) {
        if (out == null) return;
        byte[] f = new byte[11];
        f[0] = 0x5A;
        f[1] = 0x00;
        f[2] = 0x06;
        f[3] = 0x00;
        f[4] = (byte) group;
        f[5] = (byte) cmd;
        f[6] = (byte) p1;
        f[7] = (byte) p2;
        f[8] = (byte) value;
        int crc = crc16Xmodem(f, 9);
        f[9] = (byte) (crc >> 8);
        f[10] = (byte) crc;
        try {
            out.write(f);
            out.flush();
        } catch (IOException e) {
            for (Listener l : listeners) l.onError("发送失败: " + e.getMessage());
        }
    }

    private synchronized void writeAscii(String s) {
        if (out == null) return;
        try {
            out.write(s.getBytes("ASCII"));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /** CRC16/XMODEM: poly=0x1021, init=0, 无反射, 无最终 XOR */
    static int crc16Xmodem(byte[] data, int len) {
        return crc16Xmodem(data, 0, len);
    }

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

    // ---- 接收: 二进制 SPP 帧 + ASCII AT 行混合流 ----
    // 注意 0x5A 是可打印字符 'Z', 不能先按字符行解析, 必须先做字节级帧提取

    private static final int FRAME_LEN = 11; // 5A 00 06 00 group cmd p1 p2 value crc(2B)

    private void startReader() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = socket.getInputStream();
                    java.io.ByteArrayOutputStream pending =
                            new java.io.ByteArrayOutputStream();
                    StringBuilder ascii = new StringBuilder();
                    byte[] b = new byte[256];
                    while (!closing) {
                        int r = in.read(b);
                        if (r < 0) break;
                        pending.write(b, 0, r);
                        byte[] all = pending.toByteArray();
                        pending.reset();
                        int off = 0;
                        while (off < all.length) {
                            int head = findFrameHead(all, off);
                            if (head < 0) {
                                // 没有帧头: 尾部最多留 3 字节防截断, 其余按字符行解析
                                int keep = Math.min(3, all.length - off);
                                int asciiEnd = all.length - keep;
                                for (int i = off; i < asciiEnd; i++) {
                                    feedAscii(ascii, all[i] & 0xFF);
                                }
                                if (keep > 0) pending.write(all, all.length - keep, keep);
                                break;
                            }
                            // 帧头前的杂字节按字符行解析
                            for (int i = off; i < head; i++) {
                                feedAscii(ascii, all[i] & 0xFF);
                            }
                            if (head + FRAME_LEN > all.length) { // 帧不完整, 等下一段
                                pending.write(all, head, all.length - head);
                                break;
                            }
                            int crc = ((all[head + 9] & 0xFF) << 8) | (all[head + 10] & 0xFF);
                            if (crc == crc16Xmodem(all, head, 9)) {
                                dispatchFrame(all, head);
                                off = head + FRAME_LEN;
                            } else {
                                // CRC 对不上: 假帧头(正文里的 'Z'), 只跳过这 1 字节
                                feedAscii(ascii, all[head] & 0xFF);
                                off = head + 1;
                            }
                        }
                    }
                    flushAscii(ascii);
                    if (!closing) for (Listener l : listeners) l.onDisconnected("连接已关闭");
                } catch (IOException e) {
                    if (!closing) for (Listener l : listeners) l.onDisconnected("连接断开: " + e.getMessage());
                }
                safeClose();
            }
        }, "hw-reader").start();
    }

    private static int findFrameHead(byte[] b, int from) {
        for (int i = from; i + 4 <= b.length; i++) {
            if ((b[i] & 0xFF) == 0x5A && (b[i + 1] & 0xFF) == 0x00
                    && (b[i + 2] & 0xFF) == 0x06 && (b[i + 3] & 0xFF) == 0x00) {
                return i;
            }
        }
        return -1;
    }

    private void feedAscii(StringBuilder ascii, int c) {
        if (c == 0x0A) {
            handleLine(ascii.toString().trim());
            ascii.setLength(0);
        } else if (c >= 0x20 && c < 0x7F) {
            ascii.append((char) c);
            if (ascii.length() > 160) ascii.setLength(0); // 防二进制撑爆
        }
    }

    private void flushAscii(StringBuilder ascii) {
        if (ascii.length() > 0) handleLine(ascii.toString().trim());
    }

    /** 二进制帧分发: 降噪组(0x2B)的应答回给 UI 高亮 */
    private void dispatchFrame(byte[] b, int head) {
        int group = b[head + 4] & 0xFF;
        int cmd = b[head + 5] & 0xFF;
        int value = b[head + 8] & 0xFF;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FRAME_LEN; i++) sb.append(String.format("%02X ", b[head + i]));
        Log.d("HwClient", "frame group=0x" + Integer.toHexString(group)
                + " cmd=0x" + Integer.toHexString(cmd) + " value=" + value
                + " [" + sb.toString().trim() + "]");
        if (group == GROUP_ANC && cmd == CMD_ANC_SWITCH) {
            for (Listener li : listeners) {
                li.onNoiseMode(value == 0x01 ? MODE_ANC : MODE_OFF);
            }
        }
    }

    /** 解析 +HUAWEIBATTERY= 行: 首个数字为对数, 其后 count 对 (key, value) */
    private void handleLine(String line) {
        if (line == null || line.isEmpty()) return;
        Log.d("HwClient", "line: " + line);
        final String TAG = "+HUAWEIBATTERY=";
        int idx = line.indexOf(TAG);
        if (idx < 0) return;
        String[] parts = line.substring(idx + TAG.length()).trim().split(",");
        if (parts.length < 1) return;
        int count = -1;
        try {
            count = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ignored) {
        }
        if (count <= 0) return;

        int l = 0xFF, r = 0xFF, c = 0xFF, flags = 0;
        for (int k = 0; k + 1 < parts.length && k / 2 < count; k += 2) {
            int key = -1, val = -1;
            try {
                key = Integer.parseInt(parts[k].trim());
                val = Integer.parseInt(parts[k + 1].trim());
            } catch (NumberFormatException ignored) {
            }
            if (key < 0) continue;
            switch (key) {
                case 2: l = val; break;
                case 3: if (val != 0) flags |= 1; break;
                case 4: r = val; break;
                case 5: if (val != 0) flags |= 2; break;
                case 6: c = val; break;
                case 7: if (val != 0) flags |= 4; break;
                default:
            }
        }
        for (Listener li : listeners) li.onBattery(l, r, c, flags);
    }
}

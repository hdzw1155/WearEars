package com.hdz.wearcontrol.huawei;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
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
 * 电量走 HFP AT 通道, SPP 上尽力询问(AT+HUAWEIBATTERY?), 多数机型无回包。
 */
public class HuaweiSppClient implements TwsClient {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int GROUP_ANC = 0x2B;
    private static final int CMD_ANC_SWITCH = 0x04;

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean closing = false;

    @Override
    public String brandName() {
        return "Huawei";
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
                    for (Listener l : listeners)
                        l.onError("连接失败: " + e.getMessage() + "\n(耳机需先与手表配对)");
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
        // 电量在 HFP AT 通道; SPP 上尽力询问, 多数机型无回包
        writeAscii("AT+HUAWEIBATTERY?\r\n");
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
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                else crc = (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    // ---- 接收: ASCII AT 行 + 二进制帧混合流 ----

    private void startReader() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = socket.getInputStream();
                    StringBuilder ascii = new StringBuilder();
                    byte[] b = new byte[256];
                    while (!closing) {
                        int r = in.read(b);
                        if (r < 0) break;
                        for (int i = 0; i < r; i++) {
                            int c = b[i] & 0xFF;
                            if (c == 0x0A) {
                                handleLine(ascii.toString().trim());
                                ascii.setLength(0);
                            } else if (c >= 0x20 && c < 0x7F) {
                                ascii.append((char) c);
                                if (ascii.length() > 160) ascii.setLength(0); // 防二进制撑爆
                            }
                        }
                    }
                    if (!closing) for (Listener l : listeners) l.onDisconnected("连接已关闭");
                } catch (IOException e) {
                    if (!closing) for (Listener l : listeners) l.onDisconnected("连接断开: " + e.getMessage());
                }
                safeClose();
            }
        }, "hw-reader").start();
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

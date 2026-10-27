package com.hdz.wearcontrol.vivo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 协议探针通道(仅实验仓调试用, 不进正式功能):
 * adb shell am broadcast -a com.hdz.wearcontrol.PROBE --es cmd 0x0139 --es payload 01
 * cmd 支持 0x 前缀; payload 为逗号/空格分隔的十六进制, 可省略;
 * verb 默认按耳机 cfg 自动选(可用 --ei verb 覆盖)。
 * 前提: App 已连接耳机; 回包走 GaiaClient 现有日志。
 */
public class VivoProbe extends BroadcastReceiver {

    static volatile VivoGaiaClient target;

    static void attach(VivoGaiaClient c) {
        target = c;
    }

    static void detach(VivoGaiaClient c) {
        if (target == c) target = null;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        VivoGaiaClient c = target;
        if (c == null || !c.isConnected()) {
            Log.d("GaiaClient", "probe: no active vivo connection");
            return;
        }
        int cmd = parseHexInt(intent.getStringExtra("cmd"), intent.getIntExtra("cmd", -1));
        if (cmd < 0 || cmd > 0xFFFF) {
            Log.d("GaiaClient", "probe: bad/missing cmd extra");
            return;
        }
        byte[] payload = parseHexBytes(intent.getStringExtra("payload"));
        StringBuilder sb = new StringBuilder("probe >>> cmd=0x").append(Integer.toHexString(cmd));
        if (payload != null) sb.append(" payload=").append(toHex(payload));
        sb.append(" verb=auto(cfg)");
        Log.d("GaiaClient", sb.toString());
        c.sendProbe(cmd, payload);
    }

    private static int parseHexInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.decode(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static byte[] parseHexBytes(String s) {
        if (s == null) return null;
        String clean = s.replace(",", "").replace(" ", "");
        if (clean.isEmpty()) return null;
        byte[] r = new byte[clean.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return r;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }
}

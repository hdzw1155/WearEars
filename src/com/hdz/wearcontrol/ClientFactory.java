package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;

import com.hdz.wearcontrol.huawei.HuaweiSppClient;
import com.hdz.wearcontrol.vivo.VivoGaiaClient;
import com.hdz.wearcontrol.xiaomi.XiaomiMmaClient;

/** 按蓝牙设备名挑对应品牌的客户端; 不认识的返回 null */
public final class ClientFactory {

    private ClientFactory() {
    }

    public static TwsClient create(BluetoothDevice device) {
        String n = device.getName();
        if (n == null) return null;
        String ln = n.toLowerCase();
        // 精确品牌词优先
        if (ln.contains("vivo") || ln.contains("iqoo")) {
            return new VivoGaiaClient();
        }
        if (ln.contains("freebuds") || ln.contains("huawei") || ln.contains("honor")) {
            return new HuaweiSppClient();
        }
        if (ln.contains("xiaomi") || ln.contains("redmi") || ln.contains("mibuds")) {
            return new XiaomiMmaClient();
        }
        // 泛词兜底: 走 vivo GAIA 协议(当前最全)
        if (ln.contains("tws") || ln.contains("air") || ln.contains("buds")) {
            return new VivoGaiaClient();
        }
        return null;
    }
}

package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import com.hdz.wearcontrol.huawei.HuaweiSppClient;
import com.hdz.wearcontrol.vivo.VivoGaiaClient;
import com.hdz.wearcontrol.xiaomi.XiaomiMmaClient;

/** 按蓝牙设备名挑对应品牌的客户端; 不认识的返回 null。ctx 用于华为的 HFP 电量通道 */
public final class ClientFactory {

    private ClientFactory() {
    }

    public static TwsClient create(Context ctx, BluetoothDevice device) {
        String n = device.getName();
        if (n == null) return null;
        String ln = n.toLowerCase();
        // 精确品牌词优先
        if (ln.contains("vivo") || ln.contains("iqoo")) {
            return new VivoGaiaClient();
        }
        if (ln.contains("freebuds") || ln.contains("huawei") || ln.contains("honor")) {
            HuaweiSppClient c = new HuaweiSppClient();
            if (ctx != null) c.attachContext(ctx);
            return c;
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

    /** 兼容旧调用(无 context, 华为电量通道不可用) */
    public static TwsClient create(BluetoothDevice device) {
        return create(null, device);
    }
}

package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import com.hdz.wearcontrol.huawei.HuaweiSppClient;
import com.hdz.wearcontrol.vivo.VivoGaiaClient;
import com.hdz.wearcontrol.xiaomi.XiaomiMmaClient;

import java.util.UUID;

/**
 * 驱动注册表: 品牌匹配规则内聚在各驱动的 DriverInfo 里, 这里只按序扫描。
 * 适配新品牌 = 写一个 TwsClient 实现 + 在 DRIVERS 里加一行, 别处一概不用动。
 * 顺序即优先级: 精确品牌词在前, 泛词兜底(tws/air/buds)殿后。
 */
public final class DriverRegistry {

    /** 标准 A2DP Sink 服务 UUID, 与品牌无关, 用于"像耳机"的兜底识别 */
    public static final UUID A2DP_SINK_UUID =
            UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb");

    /** 驱动自描述: 匹配词/兜底标记/实例工厂 */
    public interface DriverInfo {
        String brandName();

        /** 蓝牙设备名(转小写)包含任一词即命中 */
        String[] matchWords();

        /** 是否泛词兜底驱动(认识"像耳机"的名字, 排在精确匹配之后) */
        boolean isFallback();

        TwsClient create();
    }

    private static final DriverInfo VIVO = new DriverInfo() {
        @Override public String brandName() { return "vivo"; }
        @Override public String[] matchWords() { return new String[]{"vivo", "iqoo"}; }
        @Override public boolean isFallback() { return false; }
        @Override public TwsClient create() { return new VivoGaiaClient(); }
    };

    private static final DriverInfo HUAWEI = new DriverInfo() {
        @Override public String brandName() { return "huawei"; }
        @Override public String[] matchWords() { return new String[]{"freebuds", "huawei", "honor"}; }
        @Override public boolean isFallback() { return false; }
        @Override public TwsClient create() { return new HuaweiSppClient(); }
    };

    private static final DriverInfo XIAOMI = new DriverInfo() {
        @Override public String brandName() { return "xiaomi"; }
        @Override public String[] matchWords() { return new String[]{"xiaomi", "redmi", "mibuds"}; }
        @Override public boolean isFallback() { return false; }
        @Override public TwsClient create() { return new XiaomiMmaClient(); }
    };

    // 泛词兜底: 名字带 tws/air/buds 的陌生耳机按 vivo 协议试一把(当前协议最全)
    private static final DriverInfo VIVO_FALLBACK = new DriverInfo() {
        @Override public String brandName() { return "vivo"; }
        @Override public String[] matchWords() { return new String[]{"tws", "air", "buds"}; }
        @Override public boolean isFallback() { return true; }
        @Override public TwsClient create() { return new VivoGaiaClient(); }
    };

    private static final DriverInfo[] DRIVERS = {VIVO, HUAWEI, XIAOMI, VIVO_FALLBACK};

    private DriverRegistry() {
    }

    /** 按蓝牙名挑驱动并注入上下文; 谁也不认识返回 null */
    public static TwsClient create(Context ctx, BluetoothDevice device) {
        DriverInfo info = match(device);
        if (info == null) return null;
        TwsClient c = info.create();
        c.init(ctx);
        return c;
    }

    /** 该设备是否被任一驱动认识(含泛词兜底), 供"挑目标耳机"用 */
    public static boolean isKnownDevice(BluetoothDevice device) {
        return match(device) != null;
    }

    /** 名字完全认不出时的兜底驱动(调用方已确认设备像耳机, 如带 A2DP Sink UUID) */
    public static TwsClient createFallback() {
        return new VivoGaiaClient();
    }

    private static DriverInfo match(BluetoothDevice device) {
        String n = device.getName();
        if (n == null) return null;
        String ln = n.toLowerCase();
        for (DriverInfo d : DRIVERS) {
            for (String w : d.matchWords()) {
                if (ln.contains(w)) return d;
            }
        }
        return null;
    }
}

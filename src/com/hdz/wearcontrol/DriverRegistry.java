package com.hdz.wearcontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.ParcelUuid;

import com.hdz.wearcontrol.huawei.HuaweiSppClient;
import com.hdz.wearcontrol.vivo.VivoGaiaClient;
import com.hdz.wearcontrol.xiaomi.XiaomiMmaClient;

import java.util.Set;
import java.util.UUID;

/**
 * 驱动注册表: 品牌匹配规则内聚在各驱动的 DriverInfo 里, 这里只按序扫描。
 * 适配新品牌 = 写一个 TwsClient 实现 + 在 DRIVERS 里加一行, 别处一概不用动。
 * 顺序即优先级: 精确品牌词在前, 标准协议兜底殿后。
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

        /** 是否泛词兜底驱动(不参与"挑目标耳机"的优先级) */
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

    // 弱水时砂: RoseLink 协议(逆向文档 + 社区实机), 实验性
    private static final DriverInfo ROSE = new DriverInfo() {
        @Override public String brandName() { return "弱水时砂"; }
        @Override public String[] matchWords() {
            return new String[]{"ruoshui", "roselink", "弱水", "rosebuds", "ceramics",
                    "agate", "sportfeel", "openfun", "openfeel"};
        }
        @Override public boolean isFallback() { return false; }
        @Override public TwsClient create() { return new RoseLinkClient(); }
    };

    // 原道(NiceHCK / OriG in): SPP 私有帧, 实验性
    private static final DriverInfo NICEHCK = new DriverInfo() {
        @Override public String brandName() { return "原道"; }
        @Override public String[] matchWords() { return new String[]{"yuandao", "orig", "nicehck"}; }
        @Override public boolean isFallback() { return false; }
        @Override public TwsClient create() { return new NiceHckClient(); }
    };

    // 漫步者(EDIFIER): SPP 优先 + BLE 兜底, 双帧版本, 实验性
    private static final DriverInfo EDIFIER = new DriverInfo() {
        @Override public String brandName() { return "漫步者"; }
        @Override public String[] matchWords() {
            return new String[]{"edifier", "漫步者", "neobuds", "lollipods", "fitbuds",
                    "fitclip", "w820", "w860", "w260"};
        }
        @Override public boolean isFallback() { return false; }
        @Override public TwsClient create() { return new EdifierClient(); }
    };

    // 标准协议兜底: 认不出私有协议的耳机, 只报系统聚合电量, 不碰蓝牙连接
    private static final DriverInfo STANDARD = new DriverInfo() {
        @Override public String brandName() { return "标准协议"; }
        @Override public String[] matchWords() { return new String[0]; }
        @Override public boolean isFallback() { return true; }
        @Override public TwsClient create() { return new StandardClient(); }
    };

    private static final DriverInfo[] DRIVERS = {VIVO, HUAWEI, XIAOMI, EDIFIER, ROSE, NICEHCK, STANDARD};

    /**
     * 蓝牙名里的品牌词 → 展示名。只用于界面/日志识别"这可能是哪家的耳机",
     * 不代表有该品牌的控制协议; 真正的功能仍由 DRIVERS 里的驱动决定。
     */
    private static final String[][] BRAND_WORDS = {
            {"vivo", "vivo"}, {"iqoo", "iQOO"},
            {"freebuds", "华为"}, {"huawei", "华为"}, {"honor", "荣耀"},
            {"xiaomi", "小米"}, {"redmi", "Redmi"}, {"mibuds", "小米"},
            {"edifier", "漫步者"}, {"漫步者", "漫步者"},
            {"nicehck", "原道"}, {"yuandao", "原道"}, {"原道", "原道"},
            {"ruoshui", "弱水时砂"}, {"弱水", "弱水时砂"}, {"roselink", "弱水时砂"},
            {"qcy", "QCY"},
            {"baseus", "倍思"}, {"倍思", "倍思"},
            {"ugreen", "绿联"}, {"绿联", "绿联"},
            {"soundcore", "声阔"}, {"声阔", "声阔"},
            {"1more", "万魔"}, {"万魔", "万魔"},
            {"enco", "OPPO"}, {"oppo", "OPPO"},
            {"realme", "realme"}, {"oneplus", "一加"}, {"一加", "一加"},
            {"meizu", "魅族"}, {"魅族", "魅族"},
            {"sony", "索尼"}, {"wf-", "索尼"}, {"wh-", "索尼"},
            {"jbl", "JBL"},
            {"galaxy buds", "三星"}, {"samsung", "三星"},
            {"tangmai", "唐麦"}, {"唐麦", "唐麦"},
            {"xisem", "西圣"}, {"西圣", "西圣"},
            {"nank", "南卡"}, {"南卡", "南卡"},
            {"sansui", "山水"}, {"山水", "山水"},
            {"newman", "纽曼"}, {"纽曼", "纽曼"},
            {"lenovo", "联想"}, {"联想", "联想"},
            {"kugou", "酷狗"}, {"酷狗", "酷狗"},
            {"netease", "网易云"}, {"网易云", "网易云"},
            {"shokz", "韶音"}, {"韶音", "韶音"},
            {"sennheiser", "森海塞尔"}, {"森海", "森海塞尔"},
            {"beyerdynamic", "拜雅"}, {"拜雅", "拜雅"},
            {"audio-technica", "铁三角"}, {"铁三角", "铁三角"},
            {"philips", "飞利浦"}, {"飞利浦", "飞利浦"},
            {"harman", "哈曼"}, {"akg", "AKG"},
            {"bose", "Bose"}, {"nothing", "Nothing"},
            {"redmagic", "红魔"}, {"blackshark", "黑鲨"}, {"黑鲨", "黑鲨"},
    };

    private DriverRegistry() {
    }

    /** 按蓝牙名挑驱动并注入上下文; 认不出时落到标准协议驱动(不会返回 null) */
    public static TwsClient create(Context ctx, BluetoothDevice device) {
        DriverInfo info = match(device);
        TwsClient c = info != null ? info.create() : new StandardClient();
        c.init(ctx);
        return c;
    }

    /** 该设备是否被某个真实品牌驱动认识(标准协议兜底不算), 供"挑目标耳机"用 */
    public static boolean isKnownDevice(BluetoothDevice device) {
        DriverInfo info = match(device);
        return info != null && !info.isFallback();
    }

    /** 名字完全认不出时的兜底驱动(标准协议) */
    public static TwsClient createFallback(Context ctx) {
        TwsClient c = new StandardClient();
        c.init(ctx);
        return c;
    }

    /**
     * 在已配对设备里挑目标: 名字能被真实驱动认出的优先, 再兜底带 A2DP Sink UUID 的耳机端。
     * 主界面和磁贴后台服务都要用同一套挑法, 所以放在注册表里, 别各写一份。
     * 手机/平板不是要控的耳机, 直接跳过(否则 "iQOO 12" 这类名字会被当成 vivo 耳机)。
     */
    public static BluetoothDevice findTarget(BluetoothAdapter adapter) {
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return null;
        // 1) 名字像品牌耳机 + 真收音频(A2DP Sink) = 最可靠
        for (BluetoothDevice d : bonded) {
            if (!isPhone(d) && isKnownDevice(d) && hasA2dpSink(d)) return d;
        }
        // 2) 退一步只信名字匹配(有些 ROM 上耳机 SDP 缓存没拉全)
        for (BluetoothDevice d : bonded) {
            if (!isPhone(d) && isKnownDevice(d)) return d;
        }
        // 3) 名字认不出, 但确实是收音频设备
        for (BluetoothDevice d : bonded) {
            if (!isPhone(d) && hasA2dpSink(d)) return d;
        }
        return null;
    }

    private static boolean hasA2dpSink(BluetoothDevice d) {
        ParcelUuid[] uuids = d.getUuids();
        if (uuids == null) return false;
        for (ParcelUuid u : uuids) {
            if (u.getUuid().equals(A2DP_SINK_UUID)) return true;
        }
        return false;
    }

    private static boolean isPhone(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
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

    /** 型号名 → 品牌展示名; 认不出返回 null(不代表设备不可用, 只是没有品牌标签) */
    public static String brandLabelForName(String deviceName) {
        if (deviceName == null) return null;
        String ln = deviceName.toLowerCase();
        for (String[] pair : BRAND_WORDS) {
            if (ln.contains(pair[0])) return pair[1];
        }
        return null;
    }
}

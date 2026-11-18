package com.hdz.wearcontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** 可选品牌模块注册表。删去某个品牌实现后自动使用标准只读驱动。 */
public final class DriverRegistry {
    private static final String[] MODULES = {
            "com.hdz.wearcontrol.vivo.VivoGaiaClient$Module",
            "com.hdz.wearcontrol.huawei.HuaweiSppClient$Module",
            "com.hdz.wearcontrol.xiaomi.XiaomiMmaClient$Module",
            "com.hdz.wearcontrol.oppo.OppoOpoClient$Module",
            "com.hdz.wearcontrol.EdifierClient$Module",
            "com.hdz.wearcontrol.RoseLinkClient$Module",
            "com.hdz.wearcontrol.NiceHckClient$Module",
            "com.hdz.wearcontrol.SonyClient$Module",
            "com.hdz.wearcontrol.TechnicsClient$Module"
    };
    private static final List<DriverModule> DRIVERS = loadModules();

    private static List<DriverModule> loadModules() {
        List<DriverModule> result = new ArrayList<DriverModule>();
        for (String name : MODULES) {
            try {
                result.add(Class.forName(name).asSubclass(DriverModule.class).getDeclaredConstructor().newInstance());
            } catch (ClassNotFoundException absent) {
                // 精简构建可以不包含该模块。
            } catch (ReflectiveOperationException e) {
                Log.w("Drivers", "Cannot load " + name, e);
            } catch (LinkageError e) {
                Log.w("Drivers", "Incomplete module " + name, e);
            }
        }
        return result;
    }

    public static TwsClient create(Context context, BluetoothDevice device) {
        DriverModule module = match(device);
        // 每次连接创建全新驱动。断开后的旧实例不复用，旧线程不能接触新会话字段。
        TwsClient client = module == null ? new StandardClient() : module.create();
        client.init(context);
        return client;
    }

    public static boolean isKnownDevice(BluetoothDevice device) { return match(device) != null; }

    public static TwsClient createFallback(Context context) {
        TwsClient client = new StandardClient();
        client.init(context);
        return client;
    }

    private static DriverModule match(BluetoothDevice device) {
        String name = device.getName();
        if (name == null) return null;
        for (DriverModule module : DRIVERS) if (module.matches(name)) return module;
        return null;
    }

    // 保留已有调用入口，新的页面和会话代码直接使用 DeviceSelector。
    public static BluetoothDevice findTarget(Context context, BluetoothAdapter adapter) {
        return DeviceSelector.findTarget(context, adapter);
    }
    public static BluetoothDevice findTarget(BluetoothAdapter adapter) { return DeviceSelector.findTarget(adapter); }
    public static List<BluetoothDevice> audioDevices(BluetoothAdapter adapter) { return DeviceSelector.audioDevices(adapter); }
    /**
     * 蓝牙名里的品牌词 → 展示名。只用于界面/日志识别"这可能是哪家的耳机",
     * 不代表有该品牌的控制协议; 真正的功能仍由 DRIVERS 里的驱动决定。
     */
    private static final String[][] BRAND_WORDS = {
            {"vivo", "vivo"}, {"iqoo", "iQOO"},
            {"freebuds", "华为"}, {"huawei", "华为"}, {"华为", "华为"}, {"honor", "荣耀"}, {"荣耀", "荣耀"},
            {"xiaomi", "小米"}, {"小米", "小米"}, {"redmi", "Redmi"}, {"红米", "Redmi"}, {"mibuds", "小米"},
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
            {"technics", "Technics"}, {"panasonic", "松下"}, {"松下", "松下"},
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

    /** 型号名 → 品牌展示名; 认不出返回 null(不代表设备不可用, 只是没有品牌标签) */
    public static String brandLabelForName(String deviceName) {
        if (deviceName == null) return null;
        String ln = deviceName.toLowerCase(java.util.Locale.ROOT);
        for (String[] pair : BRAND_WORDS) {
            if (ln.contains(pair[0])) return pair[1];
        }
        return null;
    }
    private DriverRegistry() { }
}

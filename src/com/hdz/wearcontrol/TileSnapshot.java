package com.hdz.wearcontrol;

/**
 * 磁贴数据快照: 只读值对象, 描述"最后一次与耳机成功通讯时"的样子。
 *
 * <p>约定(0.3.3~0.3.5 的教训, 别改):
 * <ul>
 *   <li>电量永远显示最近一次查询到的值, 不按当前连接状态强制归 --。
 *       旧版拿连接状态去改电量显示, 状态判断本身会抖, 结果磁贴显示来回跳。</li>
 *   <li>没连过时电量是 -1(未知), 界面显示 "--"; 耳机上报 0xFF 也按未知处理。</li>
 *   <li>充电标志同理: 不知道就是 {@link #UNKNOWN}, 绝不当成"没在充电"去覆盖已有值。</li>
 *   <li>多品牌: 驱动能力位 {@link #caps} 决定磁贴显示哪些区(单电量/降噪/空间音频/DeepX)。
 *       能力位为 0 表示"还没连过, 不知道支持什么", 磁贴只显示名字+时间+打开按钮。</li>
 * </ul>
 */
final class TileSnapshot {

    /** 未知电量/未知档位/未知充电标志的哨兵值 */
    static final int UNKNOWN = -1;

    /** 充电标志位: 来自 TwsClient.onBattery 第 4 参 f 的低 3 位 */
    static final int FLAG_CHARGE_LEFT = 0x01;
    static final int FLAG_CHARGE_RIGHT = 0x02;
    static final int FLAG_CHARGE_CASE = 0x04;
    /** 合法充电标志的上界(只有低 3 位有意义) */
    private static final int CHARGE_MASK = 0x07;
    /** 能力位掩码: 只保留磁贴认识的位, 防止驱动瞎塞 */
    private static final int CAPS_MASK = TwsClient.CAP_NOISE | TwsClient.CAP_BATTERY
            | TwsClient.CAP_SPATIAL | TwsClient.CAP_DEEPX | TwsClient.CAP_BATTERY_SINGLE;

    /** 耳机名; 从没连过时是默认名 */
    final String name;
    /** 上一次成功通讯的时间(毫秒), 0 = 从没连过 */
    final long lastOk;
    /** 左耳/右耳/充电盒电量, 0..100; 未知为 {@link #UNKNOWN} */
    final int battL;
    final int battR;
    final int battC;
    /** 整机总电量(单电池设备用), 0..100; 未知为 {@link #UNKNOWN} */
    final int battTotal;
    /** 充电标志 {@link #FLAG_CHARGE_LEFT} 等按位或; 未知为 {@link #UNKNOWN} */
    final int chargeFlags;
    /** 降噪档位 TwsClient.MODE_*, 未知为 {@link #UNKNOWN} */
    final int mode;
    /** 驱动能力位 TwsClient.CAP_*, 0 = 还没连过 */
    final int caps;
    /** 驱动的验证等级 TwsClient.VERIF_REAL / VERIF_UNTESTED */
    final int verifLevel;

    TileSnapshot(String name, long lastOk, int battL, int battR, int battC, int battTotal,
                 int chargeFlags, int mode, int caps, int verifLevel) {
        this.name = name != null ? name : "";
        this.lastOk = lastOk;
        this.battL = saneBattery(battL);
        this.battR = saneBattery(battR);
        this.battC = saneBattery(battC);
        this.battTotal = saneBattery(battTotal);
        this.chargeFlags = saneChargeFlags(chargeFlags);
        this.mode = (mode >= TwsClient.MODE_ANC && mode <= TwsClient.MODE_TRANS)
                ? mode : UNKNOWN;
        this.caps = caps & CAPS_MASK;
        this.verifLevel = verifLevel;
    }

    /** 电量是否可显示(0..100); 耳机 0xFF / -1 等越界值统一当未知 */
    static boolean batteryKnown(int pct) {
        return pct >= 0 && pct <= 100;
    }

    static int saneBattery(int pct) {
        return batteryKnown(pct) ? pct : UNKNOWN;
    }

    /**
     * 充电标志只认低 3 位; 越界值(例如老固件用 0xFF 表示"不知道")一律当未知,
     * 否则 0xFF 会让三个环全部显示"充电中", 凭空撒谎。
     */
    static int saneChargeFlags(int f) {
        return (f >= 0 && f <= CHARGE_MASK) ? f : UNKNOWN;
    }

    /** 某个部件此刻是否在充电; 标志未知时永远返回 false(宁可不画) */
    boolean charging(int flag) {
        return chargeFlags != UNKNOWN && (chargeFlags & flag) != 0;
    }

    boolean hasBattery() {
        return batteryKnown(battL) || batteryKnown(battR) || batteryKnown(battC)
                || batteryKnown(battTotal);
    }

    /** 驱动是不是只有整机总电量(单电池设备/标准协议兜底) */
    boolean isSingleBattery() {
        return (caps & TwsClient.CAP_BATTERY_SINGLE) != 0;
    }

    boolean hasCapability(int cap) {
        return (caps & cap) != 0;
    }

    /**
     * 资源缓存版本号: 仪表盘是运行时画的位图, 只有电量变化才需要重画。
     * 光改档位/名字只影响布局, 不会让系统丢掉资源缓存, 所以这里不含 mode/name。
     *
     * <p>注意: 磁贴 PNG 里还画了充电闪电, 所以真正喂给系统的版本串要再带上充电标志,
     * 由 {@link TileResources#version} 统一拼, 这里只保留电量部分。
     */
    String batteryVersion() {
        if (isSingleBattery() || batteryKnown(battTotal)) {
            return "t" + battTotal;
        }
        return battL + "_" + battR + "_" + battC;
    }

    @Override
    public String toString() {
        return "TileSnapshot{name=" + name + ", lastOk=" + lastOk
                + ", batt=" + battL + "/" + battR + "/" + battC + "/" + battTotal
                + ", charge=" + chargeFlags + ", mode=" + mode
                + ", caps=" + caps + ", verif=" + verifLevel + "}";
    }
}

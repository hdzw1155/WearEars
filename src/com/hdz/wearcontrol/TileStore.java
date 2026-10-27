package com.hdz.wearcontrol;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.wear.tiles.TileService;

/**
 * 磁贴数据契约: 全 App 只有这里知道磁贴读写哪些键。
 *
 * <p>磁贴展示的是"最后一次成功通讯时的快照"(耳机名/电量/充电标志/档位/时间/能力位)。
 * 写方法返回"值是否真的变了", 调用方据此决定要不要 {@link #push} —
 * 客户端每次轮询都会回调电量, 值没变还推一次的话, 系统每 30 秒就重画一遍磁贴, 纯浪费电。
 */
final class TileStore {

    /** 本 App 所有配置/快照共用的 SharedPreferences 文件名(主界面也在用, 别改) */
    static final String PREFS = "cfg";

    private static final String KEY_NAME = "tile_name";
    private static final String KEY_LAST_OK = "tile_last_ok";
    private static final String KEY_BATT_L = "tile_batt_l";
    private static final String KEY_BATT_R = "tile_batt_r";
    private static final String KEY_BATT_C = "tile_batt_c";
    private static final String KEY_BATT_TOTAL = "tile_batt_total";
    private static final String KEY_CHARGE = "tile_charge";
    private static final String KEY_MODE = "tile_mode";
    /** 最近一次连接的驱动能力位: 磁贴靠它决定显示哪些区 */
    private static final String KEY_CAPS = "tile_caps";
    /** 最近一次连接的驱动验证等级 */
    private static final String KEY_VERIF = "tile_verif";

    private TileStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String defaultName(Context ctx) {
        try {
            return ctx.getString(R.string.tile_default_name);
        } catch (RuntimeException e) {
            return "TWS 耳机";
        }
    }

    /** 读出磁贴要展示的全部数据(一次读齐, 磁贴请求里不会再摸第二遍 prefs) */
    static TileSnapshot read(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return new TileSnapshot(
                p.getString(KEY_NAME, defaultName(ctx)),
                p.getLong(KEY_LAST_OK, 0L),
                p.getInt(KEY_BATT_L, TileSnapshot.UNKNOWN),
                p.getInt(KEY_BATT_R, TileSnapshot.UNKNOWN),
                p.getInt(KEY_BATT_C, TileSnapshot.UNKNOWN),
                p.getInt(KEY_BATT_TOTAL, TileSnapshot.UNKNOWN),
                p.getInt(KEY_CHARGE, TileSnapshot.UNKNOWN),
                p.getInt(KEY_MODE, TileSnapshot.UNKNOWN),
                p.getInt(KEY_CAPS, 0),
                p.getInt(KEY_VERIF, TwsClient.VERIF_UNTESTED));
    }

    /**
     * {@link #read} 的兜底版: 磁贴服务里读 prefs 万一抛异常, 也要给出一份能渲染的空快照,
     * 不能让异常逃到系统回调线程(那会直接崩进程)。
     */
    static TileSnapshot readOrEmpty(Context ctx) {
        try {
            return read(ctx);
        } catch (RuntimeException e) {
            // 落到下面的默认快照
        }
        return new TileSnapshot(defaultName(ctx), 0L,
                TileSnapshot.UNKNOWN, TileSnapshot.UNKNOWN, TileSnapshot.UNKNOWN,
                TileSnapshot.UNKNOWN, TileSnapshot.UNKNOWN, TileSnapshot.UNKNOWN,
                0, TwsClient.VERIF_UNTESTED);
    }

    /**
     * 每次与耳机真实通讯成功(连上/切档/收到电量)都叫一声, 磁贴的"上一次连接"读它。
     *
     * @return 磁贴显示的分钟数是否变了: 变了才值得 push, 否则磁贴上的时间会一直停在连上那一刻
     */
    static boolean noteOk(Context ctx) {
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(ctx);
        long prev = p.getLong(KEY_LAST_OK, 0L);
        p.edit().putLong(KEY_LAST_OK, now).apply();
        return prev / 60000L != now / 60000L;
    }

    /**
     * 保存左右耳+盒电量与充电标志(分体电量设备)。
     *
     * @param chargeFlags TwsClient.onBattery 第 4 参 f: bit0 左耳 / bit1 右耳 / bit2 充电盒,
     *                    未知传 {@link TileSnapshot#UNKNOWN}
     * @return 电量或充电标志是否发生变化(调用方据此决定是否 push)
     */
    static boolean saveBattery(Context ctx, int l, int r, int c, int chargeFlags) {
        int nl = TileSnapshot.saneBattery(l);
        int nr = TileSnapshot.saneBattery(r);
        int nc = TileSnapshot.saneBattery(c);
        int nf = TileSnapshot.saneChargeFlags(chargeFlags);
        SharedPreferences p = prefs(ctx);
        if (p.getInt(KEY_BATT_L, TileSnapshot.UNKNOWN) == nl
                && p.getInt(KEY_BATT_R, TileSnapshot.UNKNOWN) == nr
                && p.getInt(KEY_BATT_C, TileSnapshot.UNKNOWN) == nc
                && p.getInt(KEY_CHARGE, TileSnapshot.UNKNOWN) == nf) {
            return false;
        }
        p.edit()
                .putInt(KEY_BATT_L, nl)
                .putInt(KEY_BATT_R, nr)
                .putInt(KEY_BATT_C, nc)
                .putInt(KEY_CHARGE, nf)
                .apply();
        return true;
    }

    /**
     * 保存整机总电量(标准协议兜底/单电池头戴)。
     *
     * @return 总电量是否变化
     */
    static boolean saveBatterySingle(Context ctx, int total) {
        int nt = TileSnapshot.saneBattery(total);
        SharedPreferences p = prefs(ctx);
        if (p.getInt(KEY_BATT_TOTAL, TileSnapshot.UNKNOWN) == nt) return false;
        p.edit().putInt(KEY_BATT_TOTAL, nt).apply();
        return true;
    }

    /** @return 档位是否发生变化 */
    static boolean saveMode(Context ctx, int mode) {
        SharedPreferences p = prefs(ctx);
        if (mode < TwsClient.MODE_ANC || mode > TwsClient.MODE_TRANS) return false;
        if (p.getInt(KEY_MODE, TileSnapshot.UNKNOWN) == mode) return false;
        p.edit().putInt(KEY_MODE, mode).apply();
        return true;
    }

    /** @return 名字是否发生变化 */
    static boolean saveName(Context ctx, String name) {
        String n = (name == null || name.length() == 0) ? defaultName(ctx) : name;
        SharedPreferences p = prefs(ctx);
        if (n.equals(p.getString(KEY_NAME, null))) return false;
        p.edit().putString(KEY_NAME, n).apply();
        return true;
    }

    /**
     * 保存本次连接的驱动能力位与验证等级: 磁贴布局靠它决定显示单电量还是三段电量、
     * 要不要显示降噪/空间音频/DeepX 区。
     *
     * @return 能力位或验证等级是否变化
     */
    static boolean saveCaps(Context ctx, int caps, int verifLevel) {
        SharedPreferences p = prefs(ctx);
        if (p.getInt(KEY_CAPS, 0) == caps && p.getInt(KEY_VERIF, -1) == verifLevel) {
            return false;
        }
        p.edit().putInt(KEY_CAPS, caps).putInt(KEY_VERIF, verifLevel).apply();
        return true;
    }

    /** 数据变了就叫系统立刻重画磁贴, 不等刷新周期(磁贴没添加/服务被限时静默失败) */
    static void push(Context ctx) {
        try {
            TileService.getUpdater(ctx).requestUpdate(TwsTileService.class);
        } catch (Exception ignored) {
            // 磁贴服务不可用(未添加磁贴/权限限制)时没必要打扰用户
        }
    }
}

package com.hdz.wearcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * 用户偏好: 界面风格 / 语言 / 磁贴样式。
 * 单独放一个类, 是为了让 Activity、磁贴服务、以及经典界面都能读到同一份设置。
 */
final class AppPrefs {
    static final String STORE = "cfg";

    static final int STYLE_NEW = 0;
    static final int STYLE_CLASSIC = 1;
    static final int STYLE_LIQUID = 2;
    static final int STYLE_SUPREME = 3;

    /** 新系列: 与旧三种并存, 家族(family)决定共用的画法分支 */
    static final int STYLE_NEO_NEW = 4;
    static final int STYLE_NEO_CLASSIC = 5;
    static final int STYLE_NEO_LIQUID = 6;
    static final int STYLE_MAX = 6;

    static final int STYLE_FOLLOW = -1;

    /** 新系列映射回对应的旧家族 0..2 */
    static int family(int style) {
        return style >= STYLE_NEO_NEW ? style - STYLE_NEO_NEW : style;
    }

    static boolean isNeo(int style) {
        return style >= STYLE_NEO_NEW && style <= STYLE_MAX;
    }

    static final int SCROLL_COLOR_DYNAMIC = 0;
    static final int SCROLL_COLOR_CLASSIC = 1;

    static final String LANG_ZH = "zh";
    static final String LANG_EN = "en";

    private static final String KEY_UI_STYLE = "ui_style";
    private static final String KEY_LANG = "app_lang";
    private static final String KEY_TILE_STYLE = "tile_style";
    private static final String KEY_SCROLL_COLOR = "scroll_color_style";
    /** 主界面音量条: 默认隐藏, 在设置页或原开发者设置中切换 */
    private static final String KEY_SHOW_VOLUME = "show_volume";
    private static final String KEY_SETTINGS_DAY = "settings_day_mode";
    private static final String KEY_DISCLAIMER_VERSION = "disclaimer_ack_vc";
    private static final String KEY_LOCAL_SPATIAL_ENABLED = "local_spatial_enabled";
    private static final String KEY_LOCAL_SPATIAL_SCENE = "local_spatial_scene";
    private static final String KEY_LOCAL_SPATIAL_GLOBAL_FALLBACK = "local_spatial_global_fallback";
    private static final String KEY_SPATIAL_HEAD_WIDTH_PCT = "spatial_head_width_pct";
    private static final String KEY_SPATIAL_PINNA_NOTCH_HZ = "spatial_pinna_notch_hz";
    /** 手机端按需短连接模式（防同品牌系统耳机服务抢占 RFCOMM，默认开启；手表端不启用） */
    private static final String KEY_PHONE_BURST_MODE = "phone_burst_mode";

    private AppPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    static boolean hasDisclaimer(Context context) {
        int version = versionCode(context);
        try { return Consent.current(version, prefs(context).getInt(KEY_DISCLAIMER_VERSION, 0)); }
        catch (RuntimeException unreadable) { return false; }
    }

    static void acceptDisclaimer(Context context) {
        int version = versionCode(context);
        if (version > 0 && !hasDisclaimer(context)) TileLedger.accept(prefs(context), KEY_DISCLAIMER_VERSION, version);
    }

    private static int versionCode(Context context) {
        try {
            PackageManager manager = context.getPackageManager();
            if (manager == null) return 0;
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
            return info == null ? 0 : info.versionCode;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w("AppPrefs", "Cannot read disclaimer version", e);
            return 0;
        }
    }

    static int uiStyle(Context ctx) {
        return StyleChoice.resolve(prefs(ctx).getInt(KEY_UI_STYLE, StyleChoice.MINIMAL));
    }

    static void setUiStyle(Context ctx, int style) {
        prefs(ctx).edit().putInt(KEY_UI_STYLE, StyleChoice.resolve(style)).apply();
    }

    /** 返回 "zh" 或 "en"; 默认跟随系统, 系统不是中文时用英文。 */
    static String lang(Context ctx) {
        String saved = prefs(ctx).getString(KEY_LANG, null);
        if (LANG_EN.equals(saved) || LANG_ZH.equals(saved)) {
            return saved;
        }
        return isSystemChinese(ctx) ? LANG_ZH : LANG_EN;
    }

    static void setLang(Context ctx, String lang) {
        prefs(ctx).edit().putString(KEY_LANG, LANG_EN.equals(lang) || LANG_ZH.equals(lang) ? lang : "system").apply();
    }

    static int tileStyle(Context ctx) {
        int style = tileStyleChoice(ctx);
        return style == STYLE_FOLLOW ? uiStyle(ctx) : style;
    }

    static void setTileStyle(Context ctx, int style) {
        prefs(ctx).edit().putInt(KEY_TILE_STYLE, StyleChoice.tile(style)).apply();
    }

    static int tileStyleChoice(Context ctx) {
        int style = prefs(ctx).getInt(KEY_TILE_STYLE, STYLE_FOLLOW);
        return StyleChoice.tile(style);
    }
    static String langChoice(Context ctx) { return prefs(ctx).getString(KEY_LANG, "system"); }
    static String deviceAddress(Context ctx) { return prefs(ctx).getString("device_address", ""); }
    static String snapshotAddress(Context ctx) { return prefs(ctx).getString("tile_address", ""); }
    static void setDeviceAddress(Context ctx, String address) { prefs(ctx).edit().putString("device_address", address).apply(); }

    /** 音量条是否显示(统一偏好, 默认关) */
    static boolean showVolume(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SHOW_VOLUME, false);
    }

    static void setShowVolume(Context ctx, boolean show) {
        prefs(ctx).edit().putBoolean(KEY_SHOW_VOLUME, show).apply();
    }

    static boolean settingsDayMode(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SETTINGS_DAY, false);
    }

    static void setSettingsDayMode(Context ctx, boolean day) {
        prefs(ctx).edit().putBoolean(KEY_SETTINGS_DAY, day).apply();
    }

    static int scrollColorStyle(Context ctx) {
        int style = prefs(ctx).getInt(KEY_SCROLL_COLOR, SCROLL_COLOR_DYNAMIC);
        return (style == SCROLL_COLOR_CLASSIC) ? SCROLL_COLOR_CLASSIC : SCROLL_COLOR_DYNAMIC;
    }

    static void setScrollColorStyle(Context ctx, int style) {
        prefs(ctx).edit().putInt(KEY_SCROLL_COLOR,
                (style == SCROLL_COLOR_CLASSIC) ? SCROLL_COLOR_CLASSIC : SCROLL_COLOR_DYNAMIC).apply();
    }

    static boolean localSpatialEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LOCAL_SPATIAL_ENABLED, false);
    }

    static void setLocalSpatialEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_LOCAL_SPATIAL_ENABLED, enabled).apply();
    }

    static int localSpatialScene(Context ctx) {
        return clampInt(prefs(ctx).getInt(KEY_LOCAL_SPATIAL_SCENE, 0), 0, 3);
    }

    static void setLocalSpatialScene(Context ctx, int scene) {
        prefs(ctx).edit().putInt(KEY_LOCAL_SPATIAL_SCENE, clampInt(scene, 0, 3)).apply();
    }

    static boolean localSpatialGlobalFallback(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LOCAL_SPATIAL_GLOBAL_FALLBACK, true);
    }

    static void setLocalSpatialGlobalFallback(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_LOCAL_SPATIAL_GLOBAL_FALLBACK, enabled).apply();
    }

    static int spatialHeadWidthPct(Context ctx) {
        return clampInt(prefs(ctx).getInt(KEY_SPATIAL_HEAD_WIDTH_PCT, 100), 85, 115);
    }

    static void setSpatialHeadWidthPct(Context ctx, int pct) {
        prefs(ctx).edit().putInt(KEY_SPATIAL_HEAD_WIDTH_PCT, clampInt(pct, 85, 115)).apply();
    }

    static int spatialPinnaNotchHz(Context ctx) {
        return clampInt(prefs(ctx).getInt(KEY_SPATIAL_PINNA_NOTCH_HZ, 8000), 7500, 9000);
    }

    static void setSpatialPinnaNotchHz(Context ctx, int hz) {
        prefs(ctx).edit().putInt(KEY_SPATIAL_PINNA_NOTCH_HZ, clampInt(hz, 7500, 9000)).apply();
    }

    static boolean phoneBurstMode(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PHONE_BURST_MODE, true);
    }

    static void setPhoneBurstMode(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_PHONE_BURST_MODE, enabled).apply();
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isSystemChinese(Context ctx) {
        try {
            return "zh".equals(android.content.res.Resources.getSystem().getConfiguration().locale.getLanguage());
        } catch (RuntimeException e) {
            return true;
        }
    }
}

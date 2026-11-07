package com.hdz.wearcontrol;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户偏好: 界面风格 / 语言 / 磁贴样式。
 * 单独放一个类, 是为了让 Activity、磁贴服务、以及经典界面都能读到同一份设置。
 */
final class AppPrefs {

    static final int STYLE_NEW = 0;
    static final int STYLE_CLASSIC = 1;
    static final int STYLE_LIQUID = 2;

    static final int STYLE_FOLLOW = -1;

    static final String LANG_ZH = "zh";
    static final String LANG_EN = "en";

    private static final String KEY_UI_STYLE = "ui_style";
    private static final String KEY_LANG = "app_lang";
    private static final String KEY_TILE_STYLE = "tile_style";
    /** 主界面音量条: 默认隐藏, 在设置页或原开发者设置中切换 */
    private static final String KEY_SHOW_VOLUME = "show_volume";

    private AppPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(TileStore.PREFS, Context.MODE_PRIVATE);
    }

    static int uiStyle(Context ctx) {
        int style = prefs(ctx).getInt(KEY_UI_STYLE, STYLE_NEW);

        return style >= 0 && style <= 2 ? style : STYLE_NEW;
    }

    static void setUiStyle(Context ctx, int style) {
        prefs(ctx).edit().putInt(KEY_UI_STYLE, style >= 0 && style <= 2 ? style : STYLE_NEW).apply();
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
        prefs(ctx).edit().putInt(KEY_TILE_STYLE, style == STYLE_FOLLOW ? style : Math.max(0, Math.min(2, style))).apply();
    }

    static int tileStyleChoice(Context ctx) { return prefs(ctx).getInt(KEY_TILE_STYLE, STYLE_FOLLOW); }
    static String langChoice(Context ctx) { return prefs(ctx).getString(KEY_LANG, "system"); }
    static String deviceAddress(Context ctx) { return prefs(ctx).getString("device_address", ""); }
    static void setDeviceAddress(Context ctx, String address) { prefs(ctx).edit().putString("device_address", address).apply(); }

    /** 音量条是否显示(统一偏好, 默认关) */
    static boolean showVolume(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SHOW_VOLUME, false);
    }

    static void setShowVolume(Context ctx, boolean show) {
        prefs(ctx).edit().putBoolean(KEY_SHOW_VOLUME, show).apply();
    }

    private static boolean isSystemChinese(Context ctx) {
        try {
            return "zh".equals(android.content.res.Resources.getSystem().getConfiguration().locale.getLanguage());
        } catch (RuntimeException e) {
            return true;
        }
    }
}

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
    /** 至上: 米白底 + 直角几何 + 斜向骨架的第四套界面 */
    static final int STYLE_SUPREM = 3;
    /**
     * 至上界面在当前版本先封存(交互仍有未解决的 bug, 等后续版本再开)。
     * 关闭时: 已选过至上的用户回落到极简界面, 设置页也不再展示该选项。
     */
    static final boolean SUPREM_ENABLED = false;

    static final String LANG_ZH = "zh";
    static final String LANG_EN = "en";

    private static final String KEY_UI_STYLE = "ui_style";
    private static final String KEY_LANG = "app_lang";
    private static final String KEY_TILE_STYLE = "tile_style";
    /** 主界面音量条: 默认隐藏, 开发者设置里打开后永久保留 */
    private static final String KEY_SHOW_VOLUME = "show_volume";

    private AppPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(TileStore.PREFS, Context.MODE_PRIVATE);
    }

    static int uiStyle(Context ctx) {
        int style = prefs(ctx).getInt(KEY_UI_STYLE, STYLE_NEW);
        if (!SUPREM_ENABLED && style == STYLE_SUPREM) {
            return STYLE_NEW;
        }
        return style;
    }

    static void setUiStyle(Context ctx, int style) {
        if (!SUPREM_ENABLED && style == STYLE_SUPREM) {
            style = STYLE_NEW;
        }
        prefs(ctx).edit().putInt(KEY_UI_STYLE, style >= 0 ? style : STYLE_NEW).apply();
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
        prefs(ctx).edit().putString(KEY_LANG, LANG_EN.equals(lang) ? LANG_EN : LANG_ZH).apply();
    }

    static int tileStyle(Context ctx) {
        return prefs(ctx).getInt(KEY_TILE_STYLE, STYLE_NEW);
    }

    static void setTileStyle(Context ctx, int style) {
        prefs(ctx).edit().putInt(KEY_TILE_STYLE, style >= 0 ? style : STYLE_NEW).apply();
    }

    /** 音量条是否显示(开发者设置开关, 默认关) */
    static boolean showVolume(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SHOW_VOLUME, false);
    }

    static void setShowVolume(Context ctx, boolean show) {
        prefs(ctx).edit().putBoolean(KEY_SHOW_VOLUME, show).apply();
    }

    private static boolean isSystemChinese(Context ctx) {
        try {
            return "zh".equals(ctx.getResources().getConfiguration().locale.getLanguage());
        } catch (RuntimeException e) {
            return true;
        }
    }
}

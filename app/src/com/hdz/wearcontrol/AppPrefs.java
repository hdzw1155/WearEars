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
    /** 多端宿主形态覆盖（默认自动探测：0=AUTO, 1=WATCH, 2=PHONE, 3=HUD） */
    private static final String KEY_HOST_FORM_OVERRIDE = "host_form_override";
    /** 局域网多端本地协同模式（0=关, 1=主机共享, 2=从机遥控） */
    private static final String KEY_MESH_MODE = "mesh_mode";
    private static final String KEY_MESH_HOST_IP = "mesh_host_ip";
    private static final String KEY_MESH_HOST_PORT = "mesh_host_port";
    private static final String KEY_MESH_PIN = "mesh_pin";
    /** 同机本地软件广播联动（默认关闭） */
    private static final String KEY_LOCAL_INTENT_BRIDGE = "local_intent_bridge";

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

    /** 多端宿主形态覆盖默认与范围（与 HostFormLogic.FORM_* 保持一致） */
    static final int HOST_FORM_AUTO = 0;
    static final int HOST_FORM_MAX = 3;
    /** 局域网多端本地协同默认与范围（与 LocalMeshManager.MODE_* / LocalMeshProtocol.DEFAULT_TCP_PORT 一致） */
    static final int MESH_MODE_OFF = 0;
    static final int MESH_MODE_MAX = 2;
    static final int MESH_DEFAULT_PORT = 19845;

    static int hostFormOverride(Context ctx) {
        try {
            int raw = prefs(ctx).getInt(KEY_HOST_FORM_OVERRIDE, HOST_FORM_AUTO);
            return (raw >= HOST_FORM_AUTO && raw <= HOST_FORM_MAX) ? raw : HOST_FORM_AUTO;
        } catch (RuntimeException ignored) {
            return HOST_FORM_AUTO;
        }
    }

    static void setHostFormOverride(Context ctx, int form) {
        int safe = (form >= HOST_FORM_AUTO && form <= HOST_FORM_MAX) ? form : HOST_FORM_AUTO;
        prefs(ctx).edit().putInt(KEY_HOST_FORM_OVERRIDE, safe).apply();
    }

    static int meshMode(Context ctx) {
        try {
            return clampInt(prefs(ctx).getInt(KEY_MESH_MODE, MESH_MODE_OFF),
                    MESH_MODE_OFF, MESH_MODE_MAX);
        } catch (RuntimeException ignored) {
            return MESH_MODE_OFF;
        }
    }

    static void setMeshMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY_MESH_MODE,
                clampInt(mode, MESH_MODE_OFF, MESH_MODE_MAX)).apply();
    }

    static String meshHostIp(Context ctx) {
        try {
            String ip = prefs(ctx).getString(KEY_MESH_HOST_IP, "");
            return ip == null ? "" : ip.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static void setMeshHostIp(Context ctx, String ip) {
        prefs(ctx).edit().putString(KEY_MESH_HOST_IP, ip == null ? "" : ip.trim()).apply();
    }

    static int meshHostPort(Context ctx) {
        try {
            int port = prefs(ctx).getInt(KEY_MESH_HOST_PORT, MESH_DEFAULT_PORT);
            return (port >= 1 && port <= 65535) ? port : MESH_DEFAULT_PORT;
        } catch (RuntimeException ignored) {
            return MESH_DEFAULT_PORT;
        }
    }

    static void setMeshHostPort(Context ctx, int port) {
        int safe = (port >= 1 && port <= 65535) ? port : MESH_DEFAULT_PORT;
        prefs(ctx).edit().putInt(KEY_MESH_HOST_PORT, safe).apply();
    }

    static String meshPin(Context ctx) {
        try {
            String pin = prefs(ctx).getString(KEY_MESH_PIN, "");
            return pin == null ? "" : pin.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static void setMeshPin(Context ctx, String pin) {
        prefs(ctx).edit().putString(KEY_MESH_PIN, pin == null ? "" : pin.trim()).apply();
    }

    static boolean localIntentBridge(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_LOCAL_INTENT_BRIDGE, false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void setLocalIntentBridge(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_LOCAL_INTENT_BRIDGE, enabled).apply();
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

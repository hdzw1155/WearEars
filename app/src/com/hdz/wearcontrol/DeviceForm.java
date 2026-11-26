package com.hdz.wearcontrol;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * 多端宿主形态探测与适配工具:
 * 区分圆表/Wear OS 手表、手机、以及智能眼镜/XR/Android TV 横屏 HUD 形态，
 * 并给出单端空间音频文案、滚动容器配置与手机居中列宽。
 */
public final class DeviceForm {
    private static final float PHONE_MAX_COLUMN_DP = 480f;

    private DeviceForm() {
    }

    /**
     * 结合用户手动覆盖项与硬件特征，解析当前宿主形态。
     *
     * @param ctx          Android Context
     * @param overridePref {@link HostFormLogic#FORM_AUTO} 或显式指定形态常量
     * @return {@link HostFormLogic#FORM_WATCH} / {@link HostFormLogic#FORM_PHONE} / {@link HostFormLogic#FORM_HUD}
     */
    public static int resolveForm(Context ctx, int overridePref) {
        if (ctx == null) {
            return HostFormLogic.resolve(overridePref, false, false, true, 0, 0);
        }

        Resources res = ctx.getResources();
        Configuration cfg = res != null ? res.getConfiguration() : null;
        DisplayMetrics dm = res != null ? res.getDisplayMetrics() : null;
        PackageManager pm = ctx.getPackageManager();

        boolean isRoundOrWatchFeature = (cfg != null && cfg.isScreenRound())
                || (pm != null && pm.hasSystemFeature(PackageManager.FEATURE_WATCH));

        boolean isTvUiMode = cfg != null
                && (cfg.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
        if (!isTvUiMode) {
            try {
                UiModeManager umm = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
                if (umm != null && umm.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                    isTvUiMode = true;
                }
            } catch (RuntimeException ignored) {
            }
        }

        boolean hasTouchscreen = pm == null || pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        int widthPx = dm != null ? dm.widthPixels : 0;
        int heightPx = dm != null ? dm.heightPixels : 0;

        return HostFormLogic.resolve(
                overridePref,
                isRoundOrWatchFeature,
                isTvUiMode,
                hasTouchscreen,
                widthPx,
                heightPx
        );
    }

    public static boolean isHud(Context ctx, int overridePref) {
        return resolveForm(ctx, overridePref) == HostFormLogic.FORM_HUD;
    }

    public static boolean isWatch(Context ctx, int overridePref) {
        return resolveForm(ctx, overridePref) == HostFormLogic.FORM_WATCH;
    }

    public static boolean isPhone(Context ctx, int overridePref) {
        return resolveForm(ctx, overridePref) == HostFormLogic.FORM_PHONE;
    }

    public static boolean isHud(Context ctx) {
        return isHud(ctx, HostFormLogic.FORM_AUTO);
    }

    public static boolean isWatch(Context ctx) {
        return isWatch(ctx, HostFormLogic.FORM_AUTO);
    }

    public static boolean isPhone(Context ctx) {
        return isPhone(ctx, HostFormLogic.FORM_AUTO);
    }

    public static int phoneColumnWidthPx(Context ctx) {
        if (ctx == null || ctx.getResources() == null) {
            return 0;
        }
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(PHONE_MAX_COLUMN_DP * density);
    }

    public static void styleScrollContainer(Context ctx, AppScrollView scroll, ArcIndicatorView indicator) {
        styleScrollContainer(ctx, scroll, indicator, HostFormLogic.FORM_AUTO);
    }

    public static void styleScrollContainer(
            Context ctx,
            AppScrollView scroll,
            ArcIndicatorView indicator,
            int overridePref
    ) {
        boolean nonWatch = !isWatch(ctx, overridePref);
        if (indicator != null) {
            if (nonWatch) {
                indicator.setActive(false);
                indicator.setVisibility(View.GONE);
            } else {
                indicator.setVisibility(View.VISIBLE);
            }
        }
        if (scroll != null) {
            scroll.setPhoneMode(nonWatch);
        }
    }

    public static String localSpatialTitle(Context ctx) {
        if (ctx == null) {
            return "";
        }
        return ctx.getString(isWatch(ctx) ? R.string.watch_spatial_title : R.string.phone_spatial_title);
    }

    public static String localSpatialLimitation(Context ctx) {
        if (ctx == null) {
            return "";
        }
        return ctx.getString(R.string.local_spatial_limit);
    }
}

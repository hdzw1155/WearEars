package com.hdz.wearcontrol;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.view.View;

/**
 * 设备形态探测与手机/手表适配工具:
 * 区分圆表/Wear OS 手表与矩形手机屏幕，并给出单端空间音频文案与手机居中列宽。
 */
public final class DeviceForm {
    private static final float PHONE_MAX_COLUMN_DP = 480f;

    private DeviceForm() {
    }

    public static boolean isWatch(Context ctx) {
        if (ctx == null) {
            return false;
        }
        if (ctx.getResources() != null) {
            Configuration cfg = ctx.getResources().getConfiguration();
            if (cfg != null && cfg.isScreenRound()) {
                return true;
            }
        }
        PackageManager pm = ctx.getPackageManager();
        return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    public static boolean isPhone(Context ctx) {
        return !isWatch(ctx);
    }

    public static int phoneColumnWidthPx(Context ctx) {
        if (ctx == null || ctx.getResources() == null) {
            return 0;
        }
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(PHONE_MAX_COLUMN_DP * density);
    }

    public static void styleScrollContainer(Context ctx, AppScrollView scroll, ArcIndicatorView indicator) {
        boolean phone = isPhone(ctx);
        if (indicator != null) {
            if (phone) {
                indicator.setActive(false);
                indicator.setVisibility(View.GONE);
            } else {
                indicator.setVisibility(View.VISIBLE);
            }
        }
        if (scroll != null) {
            scroll.setPhoneMode(phone);
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

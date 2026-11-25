package com.hdz.wearcontrol;

import android.content.Context;
import android.media.AudioManager;
import android.media.Spatializer;
import android.os.Build;

/**
 * Layer 3 OS Spatializer 只读探测器 (Android 12L / API 32+)。
 * 用于检测系统空间音频（如 Samsung 360 Audio / Android Spatializer）是否处于激活状态，
 * 以便本机单端 DSP 自动避让，防止双层空间音频叠加。
 */
final class SystemSpatializer {
    private SystemSpatializer() {
    }

    static boolean isOsSpatializerActive(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < 32) {
            return false;
        }
        try {
            AudioManager am = audioManager(ctx);
            return am != null && Api32.isActive(am);
        } catch (RuntimeException | NoSuchMethodError | NoClassDefFoundError ignored) {
            return false;
        }
    }

    static boolean isHeadTrackerAvailable(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < 33) {
            return false;
        }
        try {
            AudioManager am = audioManager(ctx);
            return am != null && Api33.isHeadTrackerAvailable(am);
        } catch (RuntimeException | NoSuchMethodError | NoClassDefFoundError ignored) {
            return false;
        }
    }

    private static AudioManager audioManager(Context ctx) {
        Context app = ctx.getApplicationContext();
        Object svc = (app != null ? app : ctx).getSystemService(Context.AUDIO_SERVICE);
        return svc instanceof AudioManager ? (AudioManager) svc : null;
    }

    private static final class Api32 {
        static boolean isActive(AudioManager am) {
            Spatializer sp = am.getSpatializer();
            return sp != null && sp.isAvailable() && sp.isEnabled();
        }
    }

    private static final class Api33 {
        static boolean isHeadTrackerAvailable(AudioManager am) {
            Spatializer sp = am.getSpatializer();
            return sp != null && sp.isAvailable() && sp.isHeadTrackerAvailable();
        }
    }
}

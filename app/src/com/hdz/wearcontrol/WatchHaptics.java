package com.hdz.wearcontrol;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.View;

/**
 * Watch haptic feedback helper tuned for Wear OS rotary ticks and boundary impacts.
 * Keeps vibrator capability probing and system haptic setting checks in one place.
 */
final class WatchHaptics {

    private final Context context;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private Vibrator vibrator;
    private VibrationEffect softTick;
    private VibrationEffect wallImpact;
    private long settingsCheckedAt = -1000L;
    private boolean hapticsEnabled = true;
    private long lastTickAt = -1000L;
    private long lastWallAt = -1000L;

    WatchHaptics(Context context) {
        this.context = context.getApplicationContext();
        initVibrator();
        initEffects();
    }

    private void initVibrator() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) {
                    vibrator = manager.getDefaultVibrator();
                }
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (RuntimeException ignored) {
            vibrator = null;
        }
    }

    private void initEffects() {
        if (vibrator == null) {
            return;
        }
        boolean amplitude = false;
        try {
            amplitude = vibrator.hasAmplitudeControl();
        } catch (RuntimeException ignored) {
        }
        softTick = amplitude
                ? VibrationEffect.createWaveform(new long[]{0L, 2L, 4L, 3L}, new int[]{0, 32, 72, 24}, -1)
                : VibrationEffect.createOneShot(5L, VibrationEffect.DEFAULT_AMPLITUDE);
        wallImpact = amplitude
                ? VibrationEffect.createWaveform(new long[]{0L, 4L, 12L, 5L}, new int[]{0, 140, 245, 90}, -1)
                : VibrationEffect.createOneShot(21L, VibrationEffect.DEFAULT_AMPLITUDE);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                int low = VibrationEffect.Composition.PRIMITIVE_LOW_TICK;
                int thud = VibrationEffect.Composition.PRIMITIVE_THUD;
                boolean[] supported = vibrator.arePrimitivesSupported(low, thud);
                int[] duration = vibrator.getPrimitiveDurations(low, thud);
                if (supported[0] && duration[0] > 0 && duration[0] <= 20) {
                    softTick = VibrationEffect.startComposition().addPrimitive(low, 0.34f).compose();
                }
                if (supported[1] && duration[1] > 0 && duration[1] <= 70) {
                    wallImpact = VibrationEffect.startComposition().addPrimitive(thud, 0.90f).compose();
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean isAllowed(View host) {
        if (vibrator == null || host == null || !host.isHapticFeedbackEnabled()
                || !host.isShown() || !host.hasWindowFocus()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (now - settingsCheckedAt >= 200L) {
            settingsCheckedAt = now;
            try {
                hapticsEnabled = Settings.System.getInt(
                        context.getContentResolver(),
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
            } catch (RuntimeException ignored) {
            }
        }
        return hapticsEnabled;
    }

    void playTick(View host) {
        long now = SystemClock.uptimeMillis();
        if (softTick == null || now - lastTickAt < 28L || now - lastWallAt < 110L || !isAllowed(host)) {
            return;
        }
        lastTickAt = now;
        vibrateSafe(softTick);
    }

    void playWall(View host) {
        long now = SystemClock.uptimeMillis();
        if (wallImpact == null || now - lastWallAt < 120L || !isAllowed(host)) {
            return;
        }
        lastWallAt = now;
        lastTickAt = now;
        vibrateSafe(wallImpact);
    }

    void cancel() {
        settingsCheckedAt = -1000L;
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void vibrateSafe(VibrationEffect effect) {
        try {
            vibrator.vibrate(effect, attributes);
        } catch (RuntimeException ignored) {
        }
    }
}

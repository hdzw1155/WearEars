package com.hdz.wearcontrol;

/**
 * Pure multi-host form-factor resolution logic (Java 11, zero Android dependencies).
 *
 * <p>Resolves the active host layout form between Wear OS watch, phone, and
 * landscape non-touch HUD (smart glasses / XR headset / Android TV).
 */
public final class HostFormLogic {
    public static final int FORM_AUTO = 0;
    public static final int FORM_WATCH = 1;
    public static final int FORM_PHONE = 2;
    public static final int FORM_HUD = 3;

    private static final int HUD_MAX_HEIGHT_PX = 1440;

    private HostFormLogic() {
    }

    /**
     * Normalizes a persisted override preference into one of the known {@code FORM_*} constants.
     */
    public static int normalizeOverride(int override) {
        if (override == FORM_WATCH || override == FORM_PHONE || override == FORM_HUD) {
            return override;
        }
        return FORM_AUTO;
    }

    /**
     * Resolves the concrete host form ({@link #FORM_WATCH}, {@link #FORM_PHONE}, or {@link #FORM_HUD}).
     *
     * @param override              user override ({@link #FORM_AUTO} for automatic detection)
     * @param isRoundOrWatchFeature true if screen is round or {@code FEATURE_WATCH} is present
     * @param isTvUiMode            true if running in television UI mode
     * @param hasTouchscreen        true if {@code FEATURE_TOUCHSCREEN} is present
     * @param widthPx               display width in pixels
     * @param heightPx              display height in pixels
     * @return one of {@link #FORM_WATCH}, {@link #FORM_PHONE}, or {@link #FORM_HUD}
     */
    public static int resolve(
            int override,
            boolean isRoundOrWatchFeature,
            boolean isTvUiMode,
            boolean hasTouchscreen,
            int widthPx,
            int heightPx
    ) {
        if (override == FORM_WATCH || override == FORM_PHONE || override == FORM_HUD) {
            return override;
        }
        if (isRoundOrWatchFeature) {
            return FORM_WATCH;
        }
        if (isTvUiMode) {
            return FORM_HUD;
        }
        if (!hasTouchscreen && heightPx > 0 && widthPx > heightPx && heightPx <= HUD_MAX_HEIGHT_PX) {
            return FORM_HUD;
        }
        return FORM_PHONE;
    }
}

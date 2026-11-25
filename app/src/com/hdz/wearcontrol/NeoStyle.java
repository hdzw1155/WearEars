package com.hdz.wearcontrol;

import android.content.Context;
import android.widget.TextView;

/**
 * 全应用 4 套 UI 风格（至上 / 新极简 / 新经典 / 新液位）的统一视觉令牌中心。
 * 主界面、设置页、360° 空间实验室与安全确认弹窗全部从这里取色与取背景，保持全端视觉一致。
 */
final class NeoStyle {

    private NeoStyle() {
    }

    static int theme(int style) {
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return R.style.Theme_Neo_Classic;
            case AppPrefs.STYLE_LIQUID: return R.style.Theme_Neo_Liquid;
            default: return R.style.Theme_Neo_Minimal;
        }
    }

    static boolean light(int style) {
        return style == AppPrefs.STYLE_SUPREME;
    }

    static int pageBg(int style) {
        return style == AppPrefs.STYLE_SUPREME ? 0xFFF4F3EE : 0xFF000000;
    }

    static int cardDrawable(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return R.drawable.bg_sup_card_paper;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return R.drawable.bg_neo_card_classic;
            case AppPrefs.STYLE_LIQUID: return R.drawable.bg_neo_card_liquid;
            default: return R.drawable.bg_neo_card_line;
        }
    }

    static int pillDrawable(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return R.drawable.bg_sup_btn_paper;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return R.drawable.bg_neo_pill_classic;
            case AppPrefs.STYLE_LIQUID: return R.drawable.bg_neo_pill_liquid;
            default: return R.drawable.bg_neo_pill_line;
        }
    }

    static int ctaDrawable(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return R.drawable.bg_sup_cta_blue;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return R.drawable.bg_neo_cta_classic;
            case AppPrefs.STYLE_LIQUID: return R.drawable.bg_neo_cta_liquid;
            default: return R.drawable.bg_neo_cta_mono;
        }
    }

    static int ctaTextColor(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return 0xFFF4F3EE;
        return AppPrefs.family(style) == AppPrefs.STYLE_NEW ? 0xFF111317 : 0xFFFFFFFF;
    }

    static int textPrimary(int style) {
        return style == AppPrefs.STYLE_SUPREME ? 0xFF111317 : 0xFFFFFFFF;
    }

    static int textDim(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return 0xFF4A505C;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return 0xFFB8CEEE;
            case AppPrefs.STYLE_LIQUID: return 0xFFB8D8CA;
            default: return 0xFFD8C79A;
        }
    }

    static int textFaint(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return 0xFF666C78;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return 0xFF7E96BA;
            case AppPrefs.STYLE_LIQUID: return 0xFF7EA393;
            default: return 0xFF9E906B;
        }
    }

    static int accentColor(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return 0xFF0D4FB8;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return 0xFF4C8DFF;
            case AppPrefs.STYLE_LIQUID: return 0xFF5EDDA6;
            default: return 0xFFF5B841;
        }
    }

    static int pulseColor(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return 0xFFDF1B22;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return 0xFF5EDDA6;
            case AppPrefs.STYLE_LIQUID: return 0xFF3DDC97;
            default: return 0xFFF5B841;
        }
    }

    static int warnBannerBg(int style) {
        return style == AppPrefs.STYLE_SUPREME ? R.drawable.bg_sup_chip : R.drawable.bg_chip_warn;
    }

    static int warnTextColor(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return 0xFFB45309;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return 0xFFFF9F0A;
            case AppPrefs.STYLE_LIQUID: return 0xFFFFB547;
            default: return 0xFFF5A524;
        }
    }

    static int dialogTheme(Context ctx) {
        boolean isLight = ctx != null && AppPrefs.uiStyle(ctx) == AppPrefs.STYLE_SUPREME;
        return dialogTheme(isLight);
    }

    static int dialogTheme(boolean isLight) {
        return isLight
                ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert;
    }

    /** 降噪滑档器: 槽色 / 滑块 / 未选中字 / 选中字 / 描边 / 圆角 */
    static void styleNoise(NoiseSegmentedView v, int style) {
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC:
                v.setPalette(0xFF1B2436, 0xFF3A80F5, 0xFFB8CEEE);
                v.setSelectedTextColor(0xFFFFFFFF);
                v.setTroughStroke(0x2E4C8DFF, 1f);
                v.setCornerRadiusDp(22f);
                break;
            case AppPrefs.STYLE_LIQUID:
                v.setPalette(0xFF182620, 0xFF22996A, 0xFFB8D8CA);
                v.setSelectedTextColor(0xFFFFFFFF);
                v.setTroughStroke(0x2E3DDC97, 1f);
                v.setCornerRadiusDp(22f);
                break;
            default:
                v.setPalette(0xFF241F14, 0xFFF5B841, 0xFFD8C79A);
                v.setSelectedTextColor(0xFF111317);
                v.setTroughStroke(0x2EF5B841, 1f);
                v.setCornerRadiusDp(22f);
                break;
        }
    }

    static int chipBg(int style, boolean selected) {
        if (style == AppPrefs.STYLE_SUPREME) {
            return selected ? R.drawable.bg_sup_chip_sel : R.drawable.bg_sup_chip;
        }
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC:
                return selected ? R.drawable.bg_neo_chip_sel_blue : R.drawable.bg_neo_chip_classic;
            case AppPrefs.STYLE_LIQUID:
                return selected ? R.drawable.bg_neo_chip_sel_green : R.drawable.bg_neo_chip_liquid;
            default:
                return selected ? R.drawable.bg_neo_chip_sel_white : R.drawable.bg_neo_chip_line;
        }
    }

    static int chipText(int style, boolean selected) {
        if (style == AppPrefs.STYLE_SUPREME) {
            return selected ? 0xFFFFFFFF : 0xFF101316;
        }
        if (AppPrefs.family(style) == AppPrefs.STYLE_NEW) {
            return selected ? 0xFF111317 : 0xFFD8C79A;
        }
        return selected ? 0xFFFFFFFF : (AppPrefs.family(style) == AppPrefs.STYLE_CLASSIC ? 0xFFB8CEEE : 0xFFB8D8CA);
    }

    /** Applies uniform chip background and text color for Neo styles and Supreme. */
    static void styleChip(TextView chip, int style, boolean selected) {
        if (chip == null) return;
        if (style == AppPrefs.STYLE_SUPREME) {
            chip.setBackgroundResource(selected ? R.drawable.bg_sup_chip_sel : R.drawable.bg_sup_chip);
            chip.setTextColor(selected ? 0xFFFFFFFF : 0xFF101316);
            return;
        }
        if (AppPrefs.isNeo(style)) {
            chip.setBackgroundResource(chipBg(style, selected));
            chip.setTextColor(chipText(style, selected));
            return;
        }
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        chip.setTextColor(selected ? 0xFFFFFFFF : 0x99F2F2F5);
    }

    static int cardColor(int style) {
        if (style == AppPrefs.STYLE_SUPREME) return 0xFFFFFFFF;
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return 0xFF121824;
            case AppPrefs.STYLE_LIQUID: return 0xFF111A16;
            default: return 0xFF18150E;
        }
    }

    /** 音量条锁定图标与主题配色 */
    static int lockColor(int style) {
        return 0xFFF2F2F5;
    }

    static int volumeFill(int style) {
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return 0xFF4C8DFF;
            case AppPrefs.STYLE_LIQUID: return 0xFF4CC38A;
            default: return 0xFFF5B841;
        }
    }

    static int volumeTrack(int style) {
        switch (AppPrefs.family(style)) {
            case AppPrefs.STYLE_CLASSIC: return 0xFF1B2436;
            case AppPrefs.STYLE_LIQUID: return 0xFF182620;
            default: return 0xFF241F14;
        }
    }
}

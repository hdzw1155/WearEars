package com.hdz.wearcontrol;

/**
 * 新系列的代码侧配色: 主题资源管 XML, 这里管自绘控件和运行时切换的背景。
 * 旧四种风格不经过这里, 保持原样。
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
        return false;
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
        if (AppPrefs.family(style) == AppPrefs.STYLE_NEW) {
            return selected ? 0xFF111317 : 0xFFD8C79A;
        }
        return selected ? 0xFFFFFFFF : (AppPrefs.family(style) == AppPrefs.STYLE_CLASSIC ? 0xFFB8CEEE : 0xFFB8D8CA);
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

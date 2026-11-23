package com.hdz.wearcontrol;

import android.content.Context;
import androidx.wear.tiles.ResourceBuilders;

/** 布局与图片共用的可还原版本，进程重建后仍按原请求返回图片。 */
final class TileRenderSpec {
    static String version(TileSnapshot s, int style, String lang) {
        return "art16~" + style + "~" + lang + "~" + s.caps + "~"
                + s.battL + "~" + s.battR + "~" + s.battC + "~" + s.battTotal + "~" + s.chargeFlags
                + "~" + s.confirmedMode + "~" + s.pendingMode + "~" + s.pendingExpiresAt() + "~" + s.connection;
    }
    static ResourceBuilders.Resources resources(Context ctx, String version) {
        String[] p = version.split("~");
        if (p.length != 13 || !"art16".equals(p[0])) return TileResources.empty(version);
        int style = Integer.parseInt(p[1]);
        if (style < StyleChoice.SUPREME || style > AppPrefs.STYLE_MAX || (!"zh".equals(p[2]) && !"en".equals(p[2]))) return TileResources.empty(version);
        TileSnapshot s = TileSnapshot.forResources(Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]), Integer.parseInt(p[9]),
                Integer.parseInt(p[3]), Integer.parseInt(p[10]), Long.parseLong(p[11]), Integer.parseInt(p[12]));
        Context localized = AppLocale.forLanguage(ctx, p[2]);
        if (s.isSingleBattery()) return TileResources.buildSingle(localized, s, style, version);
        return TileResources.build(localized, s, style, version);
    }
    private TileRenderSpec() { }
}

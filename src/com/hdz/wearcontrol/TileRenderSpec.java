package com.hdz.wearcontrol;

import android.content.Context;
import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ModifiersBuilders;
import androidx.wear.tiles.ResourceBuilders;

/** 布局与图片共用的可还原版本，进程重建后仍按原请求返回图片。 */
final class TileRenderSpec {
    static String version(TileSnapshot s, int style, String lang) {
        return "legacy4~" + style + "~" + lang + "~" + (s.isSingleBattery() ? 1 : 0) + "~"
                + s.battL + "~" + s.battR + "~" + s.battC + "~" + s.battTotal + "~" + s.chargeFlags + "~" + s.mode;
    }
    static ResourceBuilders.Resources resources(Context ctx, String version) {
        String[] p = version.split("~");
        if (p.length != 10 || !"legacy4".equals(p[0])) return TileResources.empty(version);
        int style = Integer.parseInt(p[1]);
        if (style < 0 || style > 2 || (!"zh".equals(p[2]) && !"en".equals(p[2]))) return TileResources.empty(version);
        TileSnapshot s = new TileSnapshot("", 0, Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]), Integer.parseInt(p[9]),
                "1".equals(p[3]) ? TwsClient.CAP_BATTERY_SINGLE : TwsClient.CAP_BATTERY, 0);
        Context localized = AppLocale.forLanguage(ctx, p[2]);
        if (s.isSingleBattery()) return TileResources.buildSingle(localized, s, version);
        if (style == AppPrefs.STYLE_CLASSIC) return ClassicTileResources.build(localized, s, version);
        return TileResources.build(localized, s, style, version);
    }
    /** 复用电量区域作为刷新点击区，不增添按钮，也不移动原布局。 */
    static ModifiersBuilders.Clickable refresh() {
        return new ModifiersBuilders.Clickable.Builder().setId("refresh")
                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setPackageName("com.hdz.wearcontrol")
                                .setClassName("com.hdz.wearcontrol.ModeCyclerActivity")
                                .addKeyToExtraMapping("refresh_only",
                                        new ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
                                .build()).build()).build();
    }
    private TileRenderSpec() { }
}

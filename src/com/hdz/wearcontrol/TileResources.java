package com.hdz.wearcontrol;

import android.content.Context;

import androidx.wear.tiles.ResourceBuilders;

/**
 * 磁贴资源表: 1 张复合仪表盘 PNG(运行时画) + 3 个降噪图标(直接引用 drawable)。
 *
 * <p>版本号规则见 {@link #version}: {@code a4.0-s{style}-左_右_盒}。它必须同时喂给
 * Tile.Builder.setResourcesVersion 和 Resources.Builder.setVersion, 两边不一致系统会一直
 * 重发请求; 而只要版本号不变, 手表就认旧缓存 —— 所以 <b>改画法必须 bump {@link #ART_REV}</b>,
 * 否则改了 DialPainter 也看不到任何变化。
 *
 * <p>单电池设备(标准协议兜底/单电池头戴)没有仪表盘位图, 走 {@link #buildSingle},
 * 资源表里只放模式图标, 版本号用 {@link #singleVersion}。
 */
final class TileResources {

    /** 复合仪表盘(三层环 + 全部文字) */
    static final String ID_DIAL = "dial";
    static final String ID_IC_OFF = "ic_off";
    static final String ID_IC_TRANS = "ic_trans";
    static final String ID_IC_ANC = "ic_anc";

    /** 画法版本: 视觉规格一变就 bump(旧值 a1.0 对应三个独立圆环的旧磁贴) */
    static final String ART_REV = "a4.0";

    /**
     * 上一份生成的 PNG 与它的版本号。onResourcesRequest 每次都被系统要求给"全量资源表",
     * 电量/充电标志没变时直接复用, 省掉一次 528x276 的位图绘制与 PNG 编码。
     * 只在 TileFutures 的单线程里写, volatile 只是给读端一个可见性保证。
     */
    private static volatile byte[] cachePng;
    private static volatile String cacheVersion;

    private TileResources() {
    }

    static int styleOf(Context ctx) {
        return AppPrefs.tileStyle(ctx);
    }

    /**
     * 资源版本号: 只有"会被画进 PNG 的东西"才配进版本号。
     * 档位/名字/时间都只影响布局, 不该让系统丢掉位图缓存; 充电标志会画闪电, 必须带上。
     */
    static String version(TileSnapshot s, int style) {
        String v = ART_REV + "-s" + style + "-" + s.batteryVersion();
        if (s.chargeFlags != TileSnapshot.UNKNOWN) {
            v = v + "_c" + s.chargeFlags;
        }
        return v;
    }

    /** 单电池设备(无仪表盘位图)的资源版本号 */
    static String singleVersion(TileSnapshot s) {
        String v = ART_REV + "-one-" + s.batteryVersion();
        if (s.chargeFlags != TileSnapshot.UNKNOWN) {
            v = v + "_c" + s.chargeFlags;
        }
        return v;
    }

    static ResourceBuilders.Resources build(Context ctx, TileSnapshot s, int style) {
        String ver = version(s, style);
        byte[] png = cachePng;
        if (png == null || !ver.equals(cacheVersion)) {
            png = pngFor(ctx, s, style);
            cachePng = png;
            cacheVersion = ver;
        }
        return new ResourceBuilders.Resources.Builder()
                .setVersion(ver)
                .addIdToImageMapping(ID_DIAL, inline(png))
                .addIdToImageMapping(ID_IC_OFF, androidImage(R.drawable.ic_off))
                .addIdToImageMapping(ID_IC_TRANS, androidImage(R.drawable.ic_trans))
                .addIdToImageMapping(ID_IC_ANC, androidImage(R.drawable.ic_anc))
                .build();
    }

    /**
     * 单电池设备的资源表: 布局里没有仪表盘, 只需要 3 个模式图标。
     * 没有模式能力时图标也没人引用, 多给 3 个小 png 不占什么, 保持版本号稳定。
     */
    static ResourceBuilders.Resources buildSingle(Context ctx, TileSnapshot s) {
        return new ResourceBuilders.Resources.Builder()
                .setVersion(singleVersion(s))
                .addIdToImageMapping(ID_IC_OFF, androidImage(R.drawable.ic_off))
                .addIdToImageMapping(ID_IC_TRANS, androidImage(R.drawable.ic_trans))
                .addIdToImageMapping(ID_IC_ANC, androidImage(R.drawable.ic_anc))
                .build();
    }

    /**
     * 兜底资源表: 与 {@link TileLayouts#fallback}/{@link TileLayouts#minimal} 配套,
     * 布局里没有任何图, 只保留版本号(版本号必须与布局声明的 resourcesVersion 一致)。
     */
    static ResourceBuilders.Resources empty(String version) {
        return new ResourceBuilders.Resources.Builder()
                .setVersion(version)
                .build();
    }

    static byte[] pngFor(Context ctx, TileSnapshot s, int style) {
        if (style == AppPrefs.STYLE_CLASSIC) {
            return OutlineTilePainter.png(ctx, s);
        }
        if (style == AppPrefs.STYLE_LIQUID) {
            return DialPainter.png(ctx, s);
        }
        return BarsTilePainter.png(ctx, s);
    }

    private static ResourceBuilders.ImageResource inline(byte[] png) {
        return new ResourceBuilders.ImageResource.Builder()
                .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                        .setData(png)
                        .setWidthPx(444)
                        .setHeightPx(228)
                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                        .build())
                .build();
    }

    private static ResourceBuilders.ImageResource androidImage(int drawableResId) {
        return new ResourceBuilders.ImageResource.Builder()
                .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
                        .setResourceId(drawableResId)
                        .build())
                .build();
    }
}

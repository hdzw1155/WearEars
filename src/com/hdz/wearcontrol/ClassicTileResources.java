package com.hdz.wearcontrol;

import android.content.Context;

import androidx.wear.tiles.ResourceBuilders;

/**
 * 磁贴资源表: 1 张复合仪表盘 PNG(运行时画) + 3 个降噪图标(直接引用 drawable)。
 *
 * <p>版本号规则见 {@link #version}: {@code a1.1-左_右_盒}。它必须同时喂给
 * Tile.Builder.setResourcesVersion 和 Resources.Builder.setVersion, 两边不一致系统会一直
 * 重发请求; 而只要版本号不变, 手表就认旧缓存 —— 所以 <b>改画法必须 bump {@link #ART_REV}</b>,
 * 否则改了 DialPainter 也看不到任何变化。
 */
final class ClassicTileResources {

    /** 复合仪表盘(三层环 + 全部文字) */
    static final String ID_DIAL = "dial";
    static final String ID_IC_OFF = "ic_off";
    static final String ID_IC_TRANS = "ic_trans";
    static final String ID_IC_ANC = "ic_anc";

    /** 画法版本: 视觉规格一变就 bump(旧值 a1.0 对应三个独立圆环的旧磁贴) */
    static final String ART_REV = "c1.0";

    /**
     * 上一份生成的 PNG 与它的版本号。onResourcesRequest 每次都被系统要求给"全量资源表",
     * 电量/充电标志没变时直接复用, 省掉一次 528x276 的位图绘制与 PNG 编码。
     * 只在 TileFutures 的单线程里写, volatile 只是给读端一个可见性保证。
     */
    private static volatile byte[] cachePng;
    private static volatile String cacheVersion;

    private ClassicTileResources() {
    }

    /**
     * 资源版本号: 只有"会被画进 PNG 的东西"才配进版本号。
     * 档位/名字/时间都只影响布局, 不该让系统丢掉位图缓存; 充电标志会画闪电, 必须带上。
     */
    static String version(TileSnapshot s) {
        String v = ART_REV + "-" + s.batteryVersion();
        if (s.chargeFlags != TileSnapshot.UNKNOWN) {
            v = v + "_c" + s.chargeFlags;
        }
        return v;
    }

    static ResourceBuilders.Resources build(Context ctx, TileSnapshot s) {
        String ver = version(s);
        byte[] png = cachePng;
        if (png == null || !ver.equals(cacheVersion)) {
            png = ClassicTilePainter.png(ctx, s);
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
     * 兜底资源表: 与 {@link TileLayouts#fallback}/{@link TileLayouts#minimal} 配套,
     * 布局里没有任何图, 只保留版本号(版本号必须与布局声明的 resourcesVersion 一致)。
     */
    static ResourceBuilders.Resources empty(String version) {
        return new ResourceBuilders.Resources.Builder()
                .setVersion(version)
                .build();
    }

    private static ResourceBuilders.ImageResource inline(byte[] png) {
        return new ResourceBuilders.ImageResource.Builder()
                .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                        .setData(png)
                        .setWidthPx(ClassicTilePainter.WIDTH_PX)
                        .setHeightPx(ClassicTilePainter.HEIGHT_PX)
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

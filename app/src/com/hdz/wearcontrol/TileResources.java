package com.hdz.wearcontrol;

import android.content.Context;

import androidx.wear.tiles.ResourceBuilders;

/**
 * 磁贴资源表: 1 张复合仪表盘 PNG(运行时画) + 3 个降噪图标(直接引用 drawable)。
 *
 * <p>图片按电量、充电标志、语言和画法缓存；布局资源版本由 TileRenderSpec 还原。
 * 改画法时递增 ART_REV，避免误用旧位图。
 *
 * <p>单电池设备(标准协议兜底/单电池头戴)没有仪表盘位图, 走 {@link #buildSingle},
 * 资源表里只放模式图标。
 */
final class TileResources {

    /** 复合仪表盘(三层环 + 全部文字) */
    static final String ID_DIAL = "dial";
    static final String ID_DIAL_PENDING = "dial_pending";
    static final String ID_IC_OFF = "ic_off";
    static final String ID_IC_TRANS = "ic_trans";
    static final String ID_IC_ANC = "ic_anc";

    /** 画法版本: 视觉规格一变就 bump(旧值 a1.0 对应三个独立圆环的旧磁贴) */
    static final String ART_REV = "a6.0";

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
        if (AppPrefs.family(style) == AppPrefs.STYLE_SUPREME) {
            v = v + "_m" + s.mode + "_p" + s.pendingMode + "_caps" + s.caps + "_link" + s.connection;
        }
        return v;
    }


    static synchronized ResourceBuilders.Resources build(Context ctx, TileSnapshot s, int style, String requestedVersion) {
        String ver = version(s, style) + "_" + ctx.getResources().getConfiguration().locale.getLanguage();
        byte[] png = cachePng;
        if (png == null || !ver.equals(cacheVersion)) {
            png = pngFor(ctx, s, style);
            cachePng = png;
            cacheVersion = ver;
        }
        boolean supreme = AppPrefs.family(style) == AppPrefs.STYLE_SUPREME;
        int imgW = supreme ? SuprematistTilePainter.WIDTH_PX : 444;
        int imgH = supreme ? SuprematistTilePainter.HEIGHT_PX : 228;
        ResourceBuilders.Resources.Builder res = new ResourceBuilders.Resources.Builder()
                .setVersion(requestedVersion)
                .addIdToImageMapping(ID_DIAL, inline(supreme && s.pendingMode >= 0
                        ? pngFor(ctx, s.withoutPending(), style) : png, imgW, imgH))
                .addIdToImageMapping(ID_IC_OFF, androidImage(R.drawable.ic_off))
                .addIdToImageMapping(ID_IC_TRANS, androidImage(R.drawable.ic_trans))
                .addIdToImageMapping(ID_IC_ANC, androidImage(R.drawable.ic_anc));
        if (supreme && s.pendingMode >= 0) {
            res.addIdToImageMapping(ID_DIAL_PENDING, inline(png, imgW, imgH));
        }
        return res.build();
    }

    /**
     * 单电池设备的资源表: 至上磁贴复用完整 450x450 北方工业画布, 其余样式放模式图标。
     */
    static ResourceBuilders.Resources buildSingle(Context ctx, TileSnapshot s, int style, String requestedVersion) {
        if (AppPrefs.family(style) == AppPrefs.STYLE_SUPREME) {
            return build(ctx, s, style, requestedVersion);
        }
        ResourceBuilders.Resources.Builder res = new ResourceBuilders.Resources.Builder()
                .setVersion(requestedVersion)
                .addIdToImageMapping(ID_IC_OFF, androidImage(R.drawable.ic_off))
                .addIdToImageMapping(ID_IC_TRANS, androidImage(R.drawable.ic_trans))
                .addIdToImageMapping(ID_IC_ANC, androidImage(R.drawable.ic_anc));
        return res.build();
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
        return style == StyleChoice.SUPREME ? SuprematistTilePainter.png(ctx, s) : NeoTilePainter.png(ctx, s, style);
    }

    private static ResourceBuilders.ImageResource inline(byte[] png, int widthPx, int heightPx) {
        return new ResourceBuilders.ImageResource.Builder()
                .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                        .setData(png)
                        .setWidthPx(widthPx)
                        .setHeightPx(heightPx)
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

package com.hdz.wearcontrol;

import android.content.Context;
import android.util.Log;

import androidx.wear.tiles.DeviceParametersBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.ResourceBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.tiles.TimelineBuilders;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Callable;

/**
 * 磁贴入口: 只负责响应系统的两次请求(要布局 / 要资源图), 其余都拆在旁边的类里。
 *
 * <ul>
 *   <li>读数据 → {@link TileStore#readOrEmpty} 一次读齐(SharedPreferences 是主内存快照, 便宜)</li>
 *   <li>布局树 → {@link TileLayouts}(单电池设备/多品牌按能力位出不同区)</li>
 *   <li>复合仪表盘/图标 → {@link TileResources}(在位图线程画, 不占 Binder 线程)</li>
 * </ul>
 *
 * <p>tiles-1.1.0 调用这两个回调时只接了 protobuf 异常, <b>我们的 RuntimeException 会一路逃到
 * 主线程变成崩溃</b>(不是"磁贴变红"), 所以这里每一层都必须自己兜住: 布局失败给兜底布局,
 * 兜底也失败给纯字面量, 资源失败给空资源表。
 */
public class TwsTileService extends TileService {

    private static final String TAG = "TileSvc";

    /**
     * 多久允许系统主动来要一次新数据。
     * 15 秒会逼系统整夜重画(耗电元凶); 数据真变化时用 TileStore.push() 主动推,
     * 磁贴可见时系统本来就会常常来问。
     */
    private static final long REFRESH_MS = 5 * 60 * 1000;

    /** 兜底布局/空资源表用的资源版本号(它们不含任何图片, 不跟电量走) */
    private static final String FALLBACK_VERSION = "fallback";

    @Override
    public ListenableFuture<TileBuilders.Tile> onTileRequest(
            RequestBuilders.TileRequest requestParams) {
        logDevice(requestParams.getDeviceParameters());

        // 磁贴文字跟随 App 内语言设置(设置页切中/英后磁贴不改的话会很怪)
        Context lc = AppLocale.localizedContext(this);
        final TileSnapshot snapshot = TileStore.readOrEmpty(lc);
        final int tileStyle = AppPrefs.tileStyle(lc);
        final boolean single = snapshot.isSingleBattery();
        try {
            if (single) {
                // 单电池设备: 没有三段仪表盘, 布局只放 "电量 N%" + 能力内的模式轨
                return TileFutures.immediate(tile(TileLayouts.build(lc, snapshot),
                        TileResources.singleVersion(snapshot)));
            }
            if (tileStyle == AppPrefs.STYLE_CLASSIC) {
                return TileFutures.immediate(tile(ClassicTileLayouts.build(lc, snapshot),
                        ClassicTileResources.version(snapshot)));
            }
            return TileFutures.immediate(tile(TileLayouts.build(lc, snapshot),
                    TileResources.version(snapshot, tileStyle)));
        } catch (RuntimeException e) {
            // 布局构建出任何意外都不能让整块磁贴变红: 先给能点进来的兜底布局
            Log.w(TAG, "layout failed, using fallback", e);
            try {
                return TileFutures.immediate(tile(TileLayouts.fallback(lc), FALLBACK_VERSION));
            } catch (RuntimeException e2) {
                // 连兜底都用不了(资源表异常之类): 纯字面量也要给一块, 绝不能让进程崩
                Log.w(TAG, "fallback layout failed, using minimal", e2);
                return TileFutures.immediate(tile(TileLayouts.minimal(), FALLBACK_VERSION));
            }
        }
    }

    @Override
    public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            RequestBuilders.ResourcesRequest requestParams) {
        final Context lc = AppLocale.localizedContext(this);
        final TileSnapshot snapshot = TileStore.readOrEmpty(lc);
        final int tileStyle = AppPrefs.tileStyle(lc);
        final boolean single = snapshot.isSingleBattery();
        return TileFutures.submit(new Callable<ResourceBuilders.Resources>() {
            @Override
            public ResourceBuilders.Resources call() {
                try {
                    if (single) {
                        return TileResources.buildSingle(lc, snapshot);
                    }
                    if (tileStyle == AppPrefs.STYLE_CLASSIC) {
                        return ClassicTileResources.build(lc, snapshot);
                    }
                    return TileResources.build(lc, snapshot, tileStyle);
                } catch (RuntimeException e) {
                    // 画图失败就交空资源表: 布局里引用不到的图会空着, 但不至于崩
                    Log.w(TAG, "resources failed, using empty", e);
                    return TileResources.empty(single
                            ? TileResources.singleVersion(snapshot)
                            : TileResources.version(snapshot, tileStyle));
                }
            }
        });
    }

    private static TileBuilders.Tile tile(LayoutElementBuilders.Layout layout, String version) {
        return new TileBuilders.Tile.Builder()
                .setResourcesVersion(version)
                .setFreshnessIntervalMillis(REFRESH_MS)
                .setTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(layout)
                                .build())
                        .build())
                .build();
    }

    /** 诊断用: 系统来要磁贴时把设备参数打进日志, 以后适配小屏/方屏有据可查 */
    private static void logDevice(DeviceParametersBuilders.DeviceParameters device) {
        if (device == null) {
            Log.d(TAG, "onTileRequest device=<null>");
            return;
        }
        Log.d(TAG, "onTileRequest screen=" + device.getScreenWidthDp() + "x"
                + device.getScreenHeightDp() + "dp"
                + " density=" + device.getScreenDensity()
                + " shape=" + device.getScreenShape()
                + " platform=" + device.getDevicePlatform());
    }
}

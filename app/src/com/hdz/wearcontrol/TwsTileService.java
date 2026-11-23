package com.hdz.wearcontrol;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
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

    /** 仅数据变化或进入磁贴时刷新，不安排周期任务。 */
    private static final long REFRESH_MS = 0L;

    /** 兜底布局/空资源表用的资源版本号(它们不含任何图片, 不跟电量走) */
    private static final String FALLBACK_VERSION = "fallback";

    /** 共享状态通过此可选入口通知宿主，核心模块不依赖 Tiles 库。 */
    public static void requestUpdate(Context context) {
        TileService.getUpdater(context).requestUpdate(TwsTileService.class);
    }

    @Override
    public ListenableFuture<TileBuilders.Tile> onTileRequest(
            RequestBuilders.TileRequest requestParams) {
        try { return requestTile(requestParams); }
        catch (RuntimeException e) {
            Log.w(TAG, "tile request failed", e);
            return TileFutures.immediate(tile(TileLayouts.minimal(), FALLBACK_VERSION));
        }
    }

    private ListenableFuture<TileBuilders.Tile> requestTile(RequestBuilders.TileRequest requestParams) {
        logDevice(requestParams.getDeviceParameters());

        // 磁贴文字跟随 App 内语言设置(设置页切中/英后磁贴不改的话会很怪)
        final Context lc = AppLocale.localizedContext(this);
        final String address = TileStore.deviceAddress(this);
        String clicked = requestParams.getState() == null ? "" : requestParams.getState().getLastClickableId();
        TileLedger.Click click = TileLedger.consume(this, clicked);
        if (!AppPrefs.hasDisclaimer(this)) {
            return TileFutures.immediate(tile(
                    TileLayouts.openControl(lc, R.string.tile_disclaimer_required), FALLBACK_VERSION));
        }
        boolean permitted = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        final int[] connection = {BluetoothLink.UNKNOWN};
        final TileFutures.Task<TileBuilders.Tile> result = new TileFutures.Task<TileBuilders.Tile>(
                new Callable<TileBuilders.Tile>() {
                    @Override public TileBuilders.Tile call() {
                        try {
                            int current = address.equals(TileStore.deviceAddress(lc))
                                    ? connection[0] : BluetoothLink.UNKNOWN;
                            return render(lc, TileStore.readOrEmpty(lc).forConnection(current));
                        } catch (RuntimeException e) {
                            Log.w(TAG, "tile state failed", e);
                            return tile(TileLayouts.minimal(), FALLBACK_VERSION);
                        }
                    }
                });
        BluetoothLink.query(this, address, new BluetoothLink.Callback() {
            @Override public void onResult(int state) {
                if (state == BluetoothLink.CONNECTED && permitted && click != null && TileLedger.current(TwsTileService.this, click)) {
                    ControlHub.get(TwsTileService.this).performFromTile(click.command.mode, address);
                }
                connection[0] = state;
                result.run();
            }
        });
        return result;
    }

    private TileBuilders.Tile render(Context lc, TileSnapshot snapshot) {
        final int tileStyle = AppPrefs.tileStyle(lc);
        final String version = TileRenderSpec.version(snapshot, tileStyle, AppPrefs.lang(lc));
        try {
            TileActions actions = new TileActions(lc);
            LayoutElementBuilders.Layout layout = layout(lc, snapshot, tileStyle, actions);
            if (snapshot.pendingMode < 0) return tile(layout, version);
            // 即使进程在切换中被杀，宿主也能在目标过期后恢复已确认的档位。
            return new TileBuilders.Tile.Builder().setResourcesVersion(version)
                    .setFreshnessIntervalMillis(REFRESH_MS)
                    .setTimeline(new TimelineBuilders.Timeline.Builder()
                            .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder().setLayout(layout)
                                    .setValidity(new TimelineBuilders.TimeInterval.Builder()
                                            .setStartMillis(System.currentTimeMillis())
                                            .setEndMillis(snapshot.pendingExpiresAt()).build()).build())
                            .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                    .setLayout(layout(lc, snapshot.withoutPending(), tileStyle, actions)).build())
                            .build()).build();
        } catch (RuntimeException e) {
            // 布局构建出任何意外都不能让整块磁贴变红: 先给能点进来的兜底布局
            Log.w(TAG, "layout failed, using fallback", e);
            try {
                return tile(TileLayouts.fallback(lc), FALLBACK_VERSION);
            } catch (RuntimeException e2) {
                // 连兜底都用不了(资源表异常之类): 纯字面量也要给一块, 绝不能让进程崩
                Log.w(TAG, "fallback layout failed, using minimal", e2);
                return tile(TileLayouts.minimal(), FALLBACK_VERSION);
            }
        }
    }

    private static LayoutElementBuilders.Layout layout(Context context, TileSnapshot snapshot, int style, TileActions actions) {
        return TileLayouts.build(context, snapshot, style, actions);
    }

    @Override
    public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            RequestBuilders.ResourcesRequest requestParams) {
        final String version = requestParams.getVersion();
        return TileFutures.submit(new Callable<ResourceBuilders.Resources>() {
            @Override public ResourceBuilders.Resources call() {
                try { return TileRenderSpec.resources(TwsTileService.this, version); }
                catch (RuntimeException e) {
                    Log.w(TAG, "resources failed", e);
                    return TileResources.empty(version);
                }
            }
        });
    }

    @Override public void onTileEnterEvent(androidx.wear.tiles.EventBuilders.TileEnterEvent event) {
        TileStore.push(this);
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

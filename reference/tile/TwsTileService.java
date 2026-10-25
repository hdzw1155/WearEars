// ============================================================================
// 【仅供参考，不参与编译】0.1.2.2 撤下的磁贴（表盘卡片）实现。
// 撤下原因：磁贴刷新链路（requestUpdate + 定时探测）在 Wear OS 上表现不稳，
// 且后台探测有偷连耳机占槽位的副作用。放出来只是留份底稿，想恢复的自担风险：
//   1. 依赖 jar（原由 scripts/fetch-libs.bat 拉取，脚本也已撤）：
//      tiles-1.1.0.jar、tiles-proto-1.1.0.jar、annotation-1.2.0.jar、
//      annotation-experimental-1.2.0.jar、listenablefuture-1.0.jar
//   2. AndroidManifest.xml 需要的注册段（同样注释保留）：
//      <service android:name=".TwsTileService" android:exported="true"
//          android:label="耳机控制" android:icon="@mipmap/ic_launcher"
//          android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
//          <intent-filter>
//              <action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" />
//          </intent-filter>
//          <meta-data android:name="androidx.wear.tiles.PREVIEW"
//              android:resource="@mipmap/ic_launcher" />
//      </service>
//      <activity android:name=".ModeCyclerActivity" android:exported="true"
//          android:excludeFromRecents="true" android:noHistory="true"
//          android:theme="@android:style/Theme.Translucent.NoTitleBar" />
//   3. 把本目录三个文件的注释解开、放回 src/com/hdz/wearcontrol/ 即可编译；
//      注意：本实现写于 ClientFactory 时代，品牌匹配已由 DriverRegistry 取代，
//      TileState.java 里的 ClientFactory.create(...) 需自行换成 DriverRegistry。
// 原样来自 0.1.2.1（commit 5a63520 的上一版），未做任何改写。
// ============================================================================

// package com.hdz.wearcontrol;
// 
// import androidx.wear.tiles.ActionBuilders;
// import androidx.wear.tiles.ColorBuilders;
// import androidx.wear.tiles.DimensionBuilders;
// import androidx.wear.tiles.LayoutElementBuilders;
// import androidx.wear.tiles.ModifiersBuilders;
// import androidx.wear.tiles.RequestBuilders;
// import androidx.wear.tiles.ResourceBuilders;
// import androidx.wear.tiles.TileBuilders;
// import androidx.wear.tiles.TileService;
// import androidx.wear.tiles.TimelineBuilders;
// 
// import com.google.common.util.concurrent.ListenableFuture;
// 
// import android.content.SharedPreferences;
// import android.graphics.Bitmap;
// import android.graphics.Canvas;
// import android.graphics.Paint;
// 
// import java.io.ByteArrayOutputStream;
// import java.util.concurrent.ExecutionException;
// import java.util.concurrent.Executor;
// import java.util.concurrent.TimeUnit;
// 
// /** 整页磁贴: 已连接/未连接 + 耳机名 + 三个电量环(左/右/盒) + 降噪圆钮 + 打开控制 */
// public class TwsTileService extends TileService {
// 
//     private static final long REFRESH_MS = 2 * 60 * 1000;
//     private static final String[] EARS = {"左", "右", "盒"};
// 
//     private static final class DoneFuture implements ListenableFuture<Object> {
//         private final Object value;
// 
//         DoneFuture(Object value) {
//             this.value = value;
//         }
// 
//         @Override public void addListener(Runnable listener, Executor executor) {
//             listener.run();
//         }
// 
//         @Override public boolean cancel(boolean mayInterruptIfRunning) {
//             return false;
//         }
// 
//         @Override public boolean isCancelled() {
//             return false;
//         }
// 
//         @Override public boolean isDone() {
//             return true;
//         }
// 
//         @Override public Object get() {
//             return value;
//         }
// 
//         @Override public Object get(long timeout, TimeUnit unit) {
//             return value;
//         }
//     }
// 
//     private static <T> ListenableFuture<T> done(T value) {
//         return (ListenableFuture<T>) (ListenableFuture<?>) new DoneFuture(value);
//     }
// 
//     /** 运行时画一个纯电量环(不含文字): 深色底 + 灰轨道 + 绿进度, 文字交给系统渲染 */
//     private byte[] renderRing(String label, int pct) {
//         boolean known = pct >= 0 && pct <= 100;
//         Bitmap bmp = Bitmap.createBitmap(232, 232, Bitmap.Config.ARGB_8888);
//         Canvas c = new Canvas(bmp);
//         Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
// 
//         p.setStyle(Paint.Style.FILL);
//         p.setColor(0xFF1C1C1E);
//         c.drawCircle(116, 116, 106, p);
// 
//         p.setStyle(Paint.Style.STROKE);
//         p.setStrokeWidth(16f);
//         p.setColor(0xFF3A3A3C);
//         c.drawCircle(116, 116, 100, p);
// 
//         if (known) {
//             p.setColor(0xFF30D158);
//             c.drawArc(18, 18, 214, 214, -90, 360f * pct / 100f, false, p);
//         }
// 
//         // 文字必须用 FILL: 上一步画弧后画笔停在 STROKE 模式, 直接写字会变成描边轮廓(糊成一坨的根因)
//         p.setStyle(Paint.Style.FILL);
//         p.setColor(0xFFFFFFFF);
//         p.setTextAlign(Paint.Align.CENTER);
//         p.setTextSize(known ? 50f : 54f);
//         p.setFakeBoldText(true);
//         c.drawText(label, 116, 100, p);
//         p.setTextSize(58f);
//         c.drawText(known ? pct + "%" : "--", 116, 164, p);
// 
//         ByteArrayOutputStream out = new ByteArrayOutputStream();
//         bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
//         return out.toByteArray();
//     }
// 
//     /** 电量环图片元素 */
//     private LayoutElementBuilders.LayoutElement ringImage(String resId, int dp) {
//         return new LayoutElementBuilders.Image.Builder()
//                 .setResourceId(resId)
//                 .setWidth(DimensionBuilders.dp((float) dp))
//                 .setHeight(DimensionBuilders.dp((float) dp))
//                 .build();
//     }
// 
//     /** 圆形降噪按钮: 蓝底=当前档, 深底=可切, 按钮下带名字 */
//     private LayoutElementBuilders.LayoutElement modeButton(
//             String resId, String label, int target, boolean active) {
//         int bg = active ? 0xFF3478F6 : 0xFF262628;
// 
//         return new LayoutElementBuilders.Column.Builder()
//                 .addContent(new LayoutElementBuilders.Box.Builder()
//                         .setWidth(DimensionBuilders.dp(38f))
//                         .setHeight(DimensionBuilders.dp(38f))
//                         .setModifiers(new ModifiersBuilders.Modifiers.Builder()
//                                 .setClickable(new ModifiersBuilders.Clickable.Builder()
//                                         .setId("mode_" + target)
//                                         .setOnClick(new ActionBuilders.LaunchAction.Builder()
//                                                 .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
//                                                         .setClassName("com.hdz.wearcontrol.ModeCyclerActivity")
//                                                         .setPackageName("com.hdz.wearcontrol")
//                                                         .addKeyToExtraMapping("target_mode",
//                                                                 new ActionBuilders.AndroidIntExtra.Builder()
//                                                                         .setValue(target)
//                                                                         .build())
//                                                         .build())
//                                                 .build())
//                                         .build())
//                                 .setBackground(new ModifiersBuilders.Background.Builder()
//                                         .setColor(ColorBuilders.argb(bg))
//                                         .setCorner(new ModifiersBuilders.Corner.Builder()
//                                                 .setRadius(DimensionBuilders.dp(19f))
//                                                 .build())
//                                         .build())
//                                 .build())
//                         .addContent(new LayoutElementBuilders.Image.Builder()
//                                 .setResourceId(resId)
//                                 .setWidth(DimensionBuilders.dp(16f))
//                                 .setHeight(DimensionBuilders.dp(16f))
//                                 .build())
//                         .build())
//                 .addContent(new LayoutElementBuilders.Text.Builder()
//                         .setText(label)
//                         .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
//                                 .setSize(DimensionBuilders.sp(10f))
//                                 .setColor(ColorBuilders.argb(active ? 0xFF3478F6 : 0xFF8E8E93))
//                                 .build())
//                         .build())
//                 .build();
//     }
// 
//     @Override
//     public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
//             RequestBuilders.ResourcesRequest requestParams) {
//         SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
//         int l = cfg.getInt("tile_batt_l", -1);
//         int r = cfg.getInt("tile_batt_r", -1);
//         int c = cfg.getInt("tile_batt_c", -1);
// 
//         ResourceBuilders.Resources res = new ResourceBuilders.Resources.Builder()
//                 .setVersion("1")
//                 .addIdToImageMapping("ring_l",
//                         new ResourceBuilders.ImageResource.Builder()
//                                 .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
//                                         .setData(renderRing("左", l))
//                                         .setWidthPx(232)
//                                         .setHeightPx(232)
//                                         .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
//                                         .build())
//                                 .build())
//                 .addIdToImageMapping("ring_r",
//                         new ResourceBuilders.ImageResource.Builder()
//                                 .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
//                                         .setData(renderRing("右", r))
//                                         .setWidthPx(232)
//                                         .setHeightPx(232)
//                                         .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
//                                         .build())
//                                 .build())
//                 .addIdToImageMapping("ring_c",
//                         new ResourceBuilders.ImageResource.Builder()
//                                 .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
//                                         .setData(renderRing("盒", c))
//                                         .setWidthPx(232)
//                                         .setHeightPx(232)
//                                         .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
//                                         .build())
//                                 .build())
//                 .addIdToImageMapping("ic_off",
//                         new ResourceBuilders.ImageResource.Builder()
//                                 .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
//                                         .setResourceId(R.drawable.ic_off).build())
//                                 .build())
//                 .addIdToImageMapping("ic_trans",
//                         new ResourceBuilders.ImageResource.Builder()
//                                 .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
//                                         .setResourceId(R.drawable.ic_trans).build())
//                                 .build())
//                 .addIdToImageMapping("ic_anc",
//                         new ResourceBuilders.ImageResource.Builder()
//                                 .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
//                                         .setResourceId(R.drawable.ic_anc).build())
//                                 .build())
//                 .build();
//         return done(res);
//     }
// 
//     @Override
//     public ListenableFuture<TileBuilders.Tile> onTileRequest(
//             RequestBuilders.TileRequest requestParams) {
//         SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
//         int mode = cfg.getInt("tile_mode", 1);
//         String status = TileState.liveStatus(this);
//         String name = cfg.getString("tile_name", "耳机");
//         int l = cfg.getInt("tile_batt_l", -1);
//         int r = cfg.getInt("tile_batt_r", -1);
//         int c = cfg.getInt("tile_batt_c", -1);
// 
//         LayoutElementBuilders.Layout layout =
//                 new LayoutElementBuilders.Layout.Builder()
//                         .setRoot(new LayoutElementBuilders.Box.Builder()
//                                 .setWidth(DimensionBuilders.expand())
//                                 .setHeight(DimensionBuilders.expand())
//                                 .setHorizontalAlignment(
//                                         LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
//                                 .setVerticalAlignment(
//                                         LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
//                                 .addContent(new LayoutElementBuilders.Column.Builder()
//                                         .setHorizontalAlignment(
//                                                 LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
//                                         .addContent(new LayoutElementBuilders.Text.Builder()
//                                                 .setText(status)
//                                                 .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
//                                                         .setSize(DimensionBuilders.sp(12f))
//                                                         .setColor(ColorBuilders.argb(0xFF9BE29B))
//                                                         .build())
//                                                 .build())
//                                         .addContent(new LayoutElementBuilders.Text.Builder()
//                                                 .setText(name)
//                                                 .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
//                                                         .setSize(DimensionBuilders.sp(14f))
//                                                         .setColor(ColorBuilders.argb(0xFFFFFFFF))
//                                                         .setWeight(700)
//                                                         .build())
//                                                 .build())
//                                         .addContent(new LayoutElementBuilders.Spacer.Builder()
//                                                 .setHeight(DimensionBuilders.dp(6f))
//                                                 .build())
//                                         .addContent(new LayoutElementBuilders.Row.Builder()
//                                                 .addContent(ringImage("ring_l", 56))
//                                                 .addContent(new LayoutElementBuilders.Spacer.Builder()
//                                                         .setWidth(DimensionBuilders.dp(5f))
//                                                         .build())
//                                                 .addContent(ringImage("ring_r", 56))
//                                                 .addContent(new LayoutElementBuilders.Spacer.Builder()
//                                                         .setWidth(DimensionBuilders.dp(5f))
//                                                         .build())
//                                                 .addContent(ringImage("ring_c", 56))
//                                                 .build())
//                                         .addContent(new LayoutElementBuilders.Spacer.Builder()
//                                                 .setHeight(DimensionBuilders.dp(6f))
//                                                 .build())
//                                         .addContent(new LayoutElementBuilders.Row.Builder()
//                                                 .addContent(modeButton("ic_off", "关闭", 1, mode == 1))
//                                                 .addContent(new LayoutElementBuilders.Spacer.Builder()
//                                                         .setWidth(DimensionBuilders.dp(14f))
//                                                         .build())
//                                                 .addContent(modeButton("ic_trans", "通透", 2, mode == 2))
//                                                 .addContent(new LayoutElementBuilders.Spacer.Builder()
//                                                         .setWidth(DimensionBuilders.dp(14f))
//                                                         .build())
//                                                 .addContent(modeButton("ic_anc", "降噪", 0, mode == 0))
//                                                 .build())
//                                         .addContent(new LayoutElementBuilders.Spacer.Builder()
//                                                 .setHeight(DimensionBuilders.dp(6f))
//                                                 .build())
//                                         .addContent(new LayoutElementBuilders.Box.Builder()
//                                                 .setWidth(DimensionBuilders.wrap())
//                                                 .setHeight(DimensionBuilders.wrap())
//                                                 .setModifiers(new ModifiersBuilders.Modifiers.Builder()
//                                                         .setClickable(new ModifiersBuilders.Clickable.Builder()
//                                                                 .setId("open")
//                                                                 .setOnClick(new ActionBuilders.LaunchAction.Builder()
//                                                                         .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
//                                                                                 .setClassName("com.hdz.wearcontrol.MainActivity")
//                                                                                 .setPackageName("com.hdz.wearcontrol")
//                                                                                 .build())
//                                                                         .build())
//                                                                 .build())
//                                                         .setBackground(new ModifiersBuilders.Background.Builder()
//                                                                 .setColor(ColorBuilders.argb(0xFF262628))
//                                                                 .setCorner(new ModifiersBuilders.Corner.Builder()
//                                                                         .setRadius(DimensionBuilders.dp(17f))
//                                                                         .build())
//                                                                 .build())
//                                                         .setPadding(new ModifiersBuilders.Padding.Builder()
//                                                                 .setStart(DimensionBuilders.dp(18f))
//                                                                 .setEnd(DimensionBuilders.dp(18f))
//                                                                 .setTop(DimensionBuilders.dp(7f))
//                                                                 .setBottom(DimensionBuilders.dp(7f))
//                                                                 .build())
//                                                         .build())
//                                                 .addContent(new LayoutElementBuilders.Text.Builder()
//                                                         .setText("打开控制")
//                                                         .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
//                                                                 .setSize(DimensionBuilders.sp(12f))
//                                                                 .setColor(ColorBuilders.argb(0xFFFFFFFF))
//                                                                 .build())
//                                                         .build())
//                                                 .build())
//                                         .build())
//                                 .build())
//                         .build();
// 
//         return done(new TileBuilders.Tile.Builder()
//                 .setResourcesVersion("1")
//                 .setFreshnessIntervalMillis(REFRESH_MS)
//                 .setTimeline(new TimelineBuilders.Timeline.Builder()
//                         .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
//                                 .setLayout(layout)
//                                 .build())
//                         .build())
//                 .build());
//     }
// }

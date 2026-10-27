package com.hdz.wearcontrol;

import android.content.Context;
import android.text.format.DateFormat;

import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ColorBuilders;
import androidx.wear.tiles.DimensionBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.ModifiersBuilders;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 磁贴布局: "设备名 · 时间" + 复合仪表盘 + 分段模式轨 + 打开胶囊。
 *
 * <p>规格: DSH\_存档\design\tile-a\spec.md 第 2 节。整列 176x174dp 是磁贴内容的硬上限
 * (432x432 表盘上刚好不压边), 再往里加东西会把"打开控制"挤出屏幕, 别加。
 *
 * <p>纯"数据 → 布局树"的翻译层, 不做 IO 也不碰 prefs, 方便单独对着改视觉。
 * 三个点击区 id 必须保持 "mode_"+目标档位 / "open", 磁贴重启后系统靠它们续上点击。
 */
final class TileLayouts {

    /** 点击事件 id: 打开主界面 */
    static final String ID_OPEN = "open";
    /** 点击事件 id 前缀: 直接切到某个降噪档位, 完整 id = 前缀 + 目标档位 */
    static final String ID_MODE_PREFIX = "mode_";

    private static final String PACKAGE_NAME = "com.hdz.wearcontrol";
    private static final String MODE_ACTIVITY = "com.hdz.wearcontrol.ModeCyclerActivity";
    private static final String MAIN_ACTIVITY = "com.hdz.wearcontrol.MainActivity";
    /** ModeCyclerActivity 的 Intent extra: 要切到哪个档位 */
    private static final String EXTRA_TARGET_MODE = "target_mode";

    // 颜色(spec §2.2; 与主界面同一套)
    private static final int COLOR_TEXT = 0xFFF2F2F5;
    private static final int COLOR_TEXT_2 = 0x99F2F2F5;
    private static final int COLOR_TEXT_DIM = 0x99F2F2F5;
    private static final int COLOR_FAINT = 0x61F2F2F5;
    private static final int COLOR_IDLE_TEXT = 0x99F2F2F5;
    /** 深邃科技蓝: 比主界面的 #3478F6 更收, 在黑底上不刺眼 */
    private static final int COLOR_ACCENT = 0xFF2B6CB0;
    private static final int COLOR_TROUGH = 0xFF1E1E21;
    private static final int COLOR_HAIRLINE = 0x14FFFFFF;
    /** 顶行设备名: 柔和浅灰白, 不用纯白抢戏 */
    private static final int COLOR_TITLE = 0xFFF2F2F5;
    /** 顶行小圆点(上次通讯在 24h 内亮绿, 否则暗灰): 只表达"最近同步过", 不声称实时连接 */
    private static final int COLOR_DOT_ON = 0xFF4CC38A;
    /** 次级胶囊(打开控制)的文字色: 内敛中灰 */
    private static final int COLOR_PILL_TEXT = 0x99F2F2F5;
    /** 「打开控制」胶囊底色: 深灰半透明材质感, 不用描边(描边在圆屏上像未加样式的按钮) */
    private static final int COLOR_PILL_BG = 0xFF1E1E21;

    // 尺寸(dp, spec §2.1; 整列 18+2+92+2+32+2+26 = 174)
    private static final float HEADER_H_DP = 16f;
    private static final float DIAL_W_DP = 148f;
    private static final float DIAL_H_DP = 76f;
    private static final float SEG_W_DP = 156f;
    private static final float SEG_H_DP = 30f;
    private static final float SEG_ROW_W_DP = 152f;
    private static final float SEG_ROW_H_DP = 26f;
    private static final float SEG_CELL_W_DP = 48f;
    private static final float SEG_CELL_H_DP = 26f;
    private static final float SEG_CELL_GAP_DP = 4f;
    private static final float SEG_CELL_RADIUS_DP = 12f;
    private static final float TROUGH_RADIUS_DP = 12f;
    private static final float SEG_ICON_DP = 14f;
    private static final float SEG_ICON_GAP_DP = 3f;
    private static final float PILL_W_DP = 108f;
    private static final float PILL_H_DP = 42f;
    private static final float PILL_RADIUS_DP = 12f;
    /** 主内容区间距 */
    private static final float GAP_DP = 3f;
    /** 降噪三联条 与 底部次级胶囊之间的留白(拉大层级) */
    private static final float PILL_GAP_DP = 4f;
    /** 整块内容略微下移: 圆屏顶部收得窄, 全居中时上面显挤、下面显空 */
    private static final float TOP_SHIFT_DP = 13f;
    private static final float NAME_TIME_GAP_DP = 5f;
    /** 顶行小圆点与设备名之间 */
    private static final float DOT_GAP_DP = 3f;

    // 字号(sp); 顶行时间与设备名共用 HEADER_NAME_SP(见 header() 注释: Row 没有基线对齐)
    private static final float HEADER_NAME_SP = 12f;
    private static final float SEG_LABEL_SP = 10f;
    private static final float PILL_LABEL_SP = 11f;

    /** 顶行名字最多显示的字符数: 长了会跟时间一起把 176dp 撑爆 */
    private static final int NAME_MAX_CHARS = 9;
    /** 没连过/读不到时的时间占位 */
    private static final String TIME_UNKNOWN = "--";

    private TileLayouts() {
    }

    /**
     * 主布局: 顶行 + 电量区 + 降噪模式轨 + 打开胶囊。
     * 多品牌按能力位裁剪: 单电池设备换成一整块"电量 N%", 没降噪能力就不渲染模式轨
     * (点了也切不动的按钮不如不画), 什么都没连过时只剩名字/时间/打开按钮。
     */
    static LayoutElementBuilders.Layout build(Context ctx, TileSnapshot s) {
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(gapV(TOP_SHIFT_DP))
                .addContent(header(ctx, s))
                .addContent(gapV(GAP_DP));
        if (s.isSingleBattery()) {
            col.addContent(singleBattery(ctx, s));
        } else {
            col.addContent(dial(ctx, s));
        }
        col.addContent(gapV(GAP_DP));
        if (s.hasCapability(TwsClient.CAP_NOISE)) {
            col.addContent(segmented(ctx, s));
            col.addContent(gapV(PILL_GAP_DP));
        }
        col.addContent(openPill(ctx.getString(R.string.tile_open)));
        return new LayoutElementBuilders.Layout.Builder()
                .setRoot(new LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHeight(DimensionBuilders.expand())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                        .addContent(col.build())
                        .build())
                .build();
    }

    /** 兜底布局: 上面任何一步抛异常时也别让磁贴整块变红, 至少告诉用户点进来 */
    static LayoutElementBuilders.Layout fallback(Context ctx) {
        return new LayoutElementBuilders.Layout.Builder()
                .setRoot(new LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHeight(DimensionBuilders.expand())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                        .addContent(new LayoutElementBuilders.Column.Builder()
                                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                .addContent(text(ctx.getString(R.string.app_name),
                                        14f, COLOR_TEXT, 700))
                                .addContent(gapV(GAP_DP))
                                .addContent(openPill(ctx.getString(R.string.tile_open)))
                                .build())
                        .build())
                .build();
    }

    /**
     * 最后防线: 连 {@link #fallback} 都抛异常时(资源表坏了之类)用纯字面量拼一块"点我"的磁贴。
     * 不碰任何资源、不读电量 —— 磁贴宁可少显示, 也不能让异常逃到系统回调线程崩进程。
     */
    static LayoutElementBuilders.Layout minimal() {
        return new LayoutElementBuilders.Layout.Builder()
                .setRoot(new LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHeight(DimensionBuilders.expand())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                        .addContent(new LayoutElementBuilders.Column.Builder()
                                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                .addContent(text("耳机控制", 14f, COLOR_TEXT, 700))
                                .addContent(gapV(GAP_DP))
                                .addContent(openPill("打开控制"))
                                .build())
                        .build())
                .build();
    }

    // ---- 顶行 ----

    /**
     * 顶行: 设备名 + "· 时间"。时间只是"上一次成功通讯"的时刻, 磁贴不表示实时连接,
     * 所以不加"已连接"之类的措辞(0.3.7 的教训: 猜连接状态会抖)。
     */
    private static LayoutElementBuilders.Row header(Context ctx, TileSnapshot s) {
        // 小圆点只表达"最近 24h 内成功同步过", 磁贴不声称实时连接(lastOk 只是快照时间戳)
        boolean recent = s.lastOk > 0L
                && System.currentTimeMillis() - s.lastOk < 24L * 60L * 60L * 1000L;
        return new LayoutElementBuilders.Row.Builder()
                .setHeight(DimensionBuilders.dp(HEADER_H_DP))
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                // 小圆点用 Box 画: 字符 "●" 的基线/行高跟文字不同, 会被 Row 居中对成两条水平线
                .addContent(dot(recent ? COLOR_DOT_ON : COLOR_TEXT_DIM))
                .addContent(gapH(DOT_GAP_DP))
                .addContent(text(ellipsize(shortName(s.name), NAME_MAX_CHARS),
                        HEADER_NAME_SP, COLOR_TITLE, 500))
                .addContent(gapH(NAME_TIME_GAP_DP))
                // 时间与设备名同字号: 磁贴的 Row 只有"垂直居中"没有基线对齐,
                // 两个不同字号会被各自行高居中成两条水平线(用户报的 bug)
                .addContent(text("· " + shortTime(ctx, s.lastOk),
                        HEADER_NAME_SP, COLOR_FAINT, 400))
                .build();
    }

    /** 顶行的小圆点: 纯色圆角小方块当圆用, 不依赖字体字形 */
    private static LayoutElementBuilders.Box dot(int color) {
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(5f))
                .setHeight(DimensionBuilders.dp(5f))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(color))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(2.5f))
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * 顶行只放得下短名: 去掉厂商前缀和 "TWS" 这类冗余词, "vivo TWS Air3 Pro" -> "Air3 Pro"。
     * 型号才是用户要看的, 12sp 下也不会再出现 "vivo TWS ..." 的省略号。
     */
    private static String shortName(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "";
        }
        String[] parts = raw.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String lower = part.toLowerCase(Locale.US);
            if ("vivo".equals(lower) || "iqoo".equals(lower) || "tws".equals(lower)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.length() == 0 ? raw.trim() : sb.toString();
    }

    /**
     * 时间短格式(沿用旧 TileState.lastSeenText 的语义, 只去掉"上一次连接"前缀):
     * 今天 → HH:mm(跟随系统 12/24 小时制), 隔天 → MM-dd, 从没连过 → "--"。
     */
    private static String shortTime(Context ctx, long lastOk) {
        if (lastOk <= 0L) {
            return TIME_UNKNOWN;
        }
        Date when = new Date(lastOk);
        if (isSameDay(lastOk, System.currentTimeMillis())) {
            return DateFormat.getTimeFormat(ctx).format(when);
        }
        try {
            return new SimpleDateFormat("MM-dd", Locale.getDefault()).format(when);
        } catch (RuntimeException e) {
            return TIME_UNKNOWN;
        }
    }

    private static boolean isSameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        Calendar cb = Calendar.getInstance();
        ca.setTimeInMillis(a);
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    /** 按"显示字符数"截断(中文/emoji 算 1 个), 超了补省略号; 不按 char 切, 免得切出半个汉字 */
    private static String ellipsize(String s, int maxChars) {
        if (s == null || s.length() == 0) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= maxChars) {
            return s;
        }
        int end = s.offsetByCodePoints(0, maxChars);
        return s.substring(0, end) + "…";
    }

    // ---- 仪表盘 ----

    /** 复合仪表盘: 位图由 TileResources 在 onResourcesRequest 里生成, 这里只放图 */
    private static LayoutElementBuilders.Image dial(Context ctx, TileSnapshot s) {
        ModifiersBuilders.Modifiers modifiers = new ModifiersBuilders.Modifiers.Builder()
                .setSemantics(new ModifiersBuilders.Semantics.Builder()
                        .setContentDescription(dialDescription(ctx, s))
                        .build())
                .build();
        return new LayoutElementBuilders.Image.Builder()
                .setResourceId(TileResources.ID_DIAL)
                .setWidth(DimensionBuilders.dp(DIAL_W_DP))
                .setHeight(DimensionBuilders.dp(DIAL_H_DP))
                .setModifiers(modifiers)
                .build();
    }

    /** 读屏用: "左耳 88%，右耳 86%，充电盒 92%"(PNG 里的字读屏读不到, 只能在这里补) */
    private static String dialDescription(Context ctx, TileSnapshot s) {
        StringBuilder sb = new StringBuilder();
        appendBattery(sb, label(ctx, R.string.label_left), s.battL,
                s.charging(TileSnapshot.FLAG_CHARGE_LEFT));
        sb.append('，');
        appendBattery(sb, label(ctx, R.string.label_right), s.battR,
                s.charging(TileSnapshot.FLAG_CHARGE_RIGHT));
        sb.append('，');
        appendBattery(sb, label(ctx, R.string.label_case), s.battC,
                s.charging(TileSnapshot.FLAG_CHARGE_CASE));
        return sb.toString();
    }

    private static void appendBattery(StringBuilder sb, String label, int pct, boolean charging) {
        sb.append(label).append(' ');
        if (TileSnapshot.batteryKnown(pct)) {
            sb.append(pct).append('%');
        } else {
            sb.append("--");
        }
        if (charging) {
            sb.append("（充电中）");
        }
    }

    // ---- 单电池电量区 ----

    /**
     * 单电池设备(标准协议兜底/单电池头戴)没有三段仪表盘, 用一块 148x76dp 的居中文字区代替:
     * "电量" 小标签 + 大号 "N%"。视觉高度与仪表盘一致, 下面的模式轨/打开胶囊不会跳位。
     */
    private static LayoutElementBuilders.Box singleBattery(Context ctx, TileSnapshot s) {
        String value = batteryText(s);
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(DIAL_W_DP))
                .setHeight(DimensionBuilders.dp(DIAL_H_DP))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setSemantics(new ModifiersBuilders.Semantics.Builder()
                                .setContentDescription(label(ctx, R.string.battery_title) + " " + value)
                                .build())
                        .build())
                .addContent(new LayoutElementBuilders.Column.Builder()
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(text(label(ctx, R.string.battery_title), 11f, COLOR_FAINT, 400))
                        .addContent(gapV(2f))
                        .addContent(text(value, 34f, COLOR_TITLE, 600))
                        .build())
                .build();
    }

    /** 单电池显示文本: 已知 → "76%", 未知 → "--" */
    private static String batteryText(TileSnapshot s) {
        if (TileSnapshot.batteryKnown(s.battTotal)) {
            return s.battTotal + "%";
        }
        return "--";
    }

    // ---- 分段模式轨 ----

    private static LayoutElementBuilders.Box segmented(Context ctx, TileSnapshot s) {
        LayoutElementBuilders.Row row = new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.dp(SEG_ROW_W_DP))
                .setHeight(DimensionBuilders.dp(SEG_ROW_H_DP))
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(segment(ctx, s, R.drawable.ic_off, TwsClient.MODE_OFF,
                        R.string.tile_mode_off, R.string.tile_mode_off_selected))
                .addContent(gapH(SEG_CELL_GAP_DP))
                .addContent(segment(ctx, s, R.drawable.ic_trans, TwsClient.MODE_TRANS,
                        R.string.tile_mode_trans, R.string.tile_mode_trans_selected))
                .addContent(gapH(SEG_CELL_GAP_DP))
                .addContent(segment(ctx, s, R.drawable.ic_anc, TwsClient.MODE_ANC,
                        R.string.tile_mode_anc, R.string.tile_mode_anc_selected))
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(SEG_W_DP))
                .setHeight(DimensionBuilders.dp(SEG_H_DP))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(COLOR_TROUGH))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(TROUGH_RADIUS_DP))
                                        .build())
                                .build())
                        .setBorder(new ModifiersBuilders.Border.Builder()
                                .setWidth(DimensionBuilders.dp(1f))
                                .setColor(ColorBuilders.argb(COLOR_HAIRLINE))
                                .build())
                        .build())
                .addContent(row)
                .build();
    }

    /**
     * 一格模式: 选中 = 蓝底白字, 未选中 = 无底色浅灰字。
     * id 用 "mode_" + 目标档位(关闭 1 / 通透 2 / 降噪 0), 与主界面既有的约定一致。
     */
    private static LayoutElementBuilders.Box segment(Context ctx, TileSnapshot s, int iconRes,
                                                     int target, int labelRes, int selectedLabelRes) {
        boolean active = s.mode == target;
        ModifiersBuilders.Modifiers.Builder modifiers = new ModifiersBuilders.Modifiers.Builder()
                .setClickable(new ModifiersBuilders.Clickable.Builder()
                        .setId(ID_MODE_PREFIX + target)
                        .setOnClick(new ActionBuilders.LaunchAction.Builder()
                                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                        .setClassName(MODE_ACTIVITY)
                                        .setPackageName(PACKAGE_NAME)
                                        .addKeyToExtraMapping(EXTRA_TARGET_MODE,
                                                new ActionBuilders.AndroidIntExtra.Builder()
                                                        .setValue(target)
                                                        .build())
                                        .build())
                                .build())
                        .build())
                .setSemantics(new ModifiersBuilders.Semantics.Builder()
                        .setContentDescription(ctx.getString(active ? selectedLabelRes : labelRes))
                        .build());
        if (active) {
            modifiers.setBackground(new ModifiersBuilders.Background.Builder()
                    .setColor(ColorBuilders.argb(COLOR_ACCENT))
                    .setCorner(new ModifiersBuilders.Corner.Builder()
                            .setRadius(DimensionBuilders.dp(SEG_CELL_RADIUS_DP))
                            .build())
                    .build());
        }

        LayoutElementBuilders.Row inner = new LayoutElementBuilders.Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(new LayoutElementBuilders.Image.Builder()
                        .setResourceId(iconResSrc(iconRes))
                        .setWidth(DimensionBuilders.dp(SEG_ICON_DP))
                        .setHeight(DimensionBuilders.dp(SEG_ICON_DP))
                        .build())
                .addContent(gapH(SEG_ICON_GAP_DP))
                .addContent(text(ctx.getString(labelRes), SEG_LABEL_SP,
                        active ? COLOR_TEXT : COLOR_IDLE_TEXT, 500))
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(SEG_CELL_W_DP))
                .setHeight(DimensionBuilders.dp(SEG_CELL_H_DP))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(modifiers.build())
                .addContent(inner)
                .build();
    }

    // ---- 打开胶囊 ----

    /** 次级胶囊「打开控制 ›」: 108x42dp, 圆角 12, 二级面 #1E1E21 + 1dp 8% 白描边 (视觉与主界面按钮同源) */
    private static LayoutElementBuilders.Box openPill(String label) {
        ModifiersBuilders.Modifiers modifiers = new ModifiersBuilders.Modifiers.Builder()
                .setClickable(new ModifiersBuilders.Clickable.Builder()
                        .setId(ID_OPEN)
                        .setOnClick(new ActionBuilders.LaunchAction.Builder()
                                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                        .setClassName(MAIN_ACTIVITY)
                                        .setPackageName(PACKAGE_NAME)
                                        .build())
                                .build())
                        .build())
                .setSemantics(new ModifiersBuilders.Semantics.Builder()
                        .setContentDescription(label)
                        .build())
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(COLOR_PILL_BG))
                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                .setRadius(DimensionBuilders.dp(PILL_RADIUS_DP))
                                .build())
                        .build())
                .setBorder(new ModifiersBuilders.Border.Builder()
                        .setWidth(DimensionBuilders.dp(1f))
                        .setColor(ColorBuilders.argb(COLOR_HAIRLINE))
                        .build())
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(PILL_W_DP))
                .setHeight(DimensionBuilders.dp(PILL_H_DP))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(modifiers)
                .addContent(text(label + " ›", PILL_LABEL_SP, COLOR_PILL_TEXT, 400))
                .build();
    }

    // ---- 小零件 ----

    private static LayoutElementBuilders.Text text(String s, float sp, int color, int weight) {
        LayoutElementBuilders.FontStyle.Builder font =
                new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(sp))
                        .setColor(ColorBuilders.argb(color));
        if (weight > 0) {
            font.setWeight(weight);
        }
        return new LayoutElementBuilders.Text.Builder()
                .setText(s)
                .setFontStyle(font.build())
                .build();
    }

    private static LayoutElementBuilders.Spacer gapV(float dp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(dp))
                .build();
    }

    private static LayoutElementBuilders.Spacer gapH(float dp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(dp))
                .build();
    }

    /** 布局里的图标引用与资源表里的 id 必须一致, 这里集中生成防手滑 */
    private static String iconResSrc(int drawableResId) {
        if (drawableResId == R.drawable.ic_off) return TileResources.ID_IC_OFF;
        if (drawableResId == R.drawable.ic_trans) return TileResources.ID_IC_TRANS;
        return TileResources.ID_IC_ANC;
    }

    /** 取字符串资源的安全版: 兜底布局/降级路径上绝不能因为一条文案抛异常 */
    private static String label(Context ctx, int resId) {
        try {
            return ctx.getString(resId);
        } catch (RuntimeException e) {
            return "";
        }
    }
}

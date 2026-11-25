package com.hdz.wearcontrol;

import android.content.Context;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 磁贴状态行：系统音频连接与历史同步时间分开描述，不用电量缓存推断连接。
 */
final class TileState {

    private TileState() {
    }

    static String connectionText(Context ctx, TileSnapshot snapshot) {
        if (snapshot.connection == BluetoothLink.DISCONNECTED) return ctx.getString(R.string.status_idle);
        if (snapshot.connection != BluetoothLink.CONNECTED) return ctx.getString(R.string.tile_connection_unknown);
        return ctx.getString(snapshot.pendingMode >= 0 ? R.string.tile_switching : R.string.status_connected);
    }

    /**
     * 上次成功通讯时间的文案: 今天只报时分, 隔天补月日, 从没连过显示 --。
     *
     * @param lastOk {@link TileSnapshot#lastOk}, 0 表示没连过
     */
    static String lastSeenText(Context ctx, long lastOk) {
        if (lastOk <= 0) {
            return ctx.getString(R.string.tile_last_seen, "--");
        }
        Date when = new Date(lastOk);
        String time = DateFormat.getTimeFormat(ctx).format(when);
        if (sameDay(lastOk)) {
            return ctx.getString(R.string.tile_last_seen, time);
        }
        String day = new SimpleDateFormat("MM-dd", Locale.getDefault()).format(when);
        return ctx.getString(R.string.tile_last_seen, day + " " + time);
    }

    /** 用 Calendar 直接比年月日, 免掉每次格式化 yyyyMMdd 的字符串开销 */
    private static boolean sameDay(long t) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(t);
        Calendar now = Calendar.getInstance();
        return then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
    }
}

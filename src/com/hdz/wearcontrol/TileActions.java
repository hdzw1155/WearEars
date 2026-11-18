package com.hdz.wearcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ModifiersBuilders;

/** LoadAction 在磁贴内交互；只有“打开控制”启动 Activity。 */
final class TileActions {
    private final String address;
    private final long token;

    TileActions(Context context) {
        address = TileStore.deviceAddress(context);
        SharedPreferences prefs = context.getSharedPreferences(TileStore.PREFS, Context.MODE_PRIVATE);
        token = prefs.getLong("tile_action_issued", 0L) + 1L;
        prefs.edit().putLong("tile_action_issued", token).apply();
    }

    ModifiersBuilders.Clickable mode(int mode) {
        return new ModifiersBuilders.Clickable.Builder().setId(TileCommand.id(token, mode, address))
                .setOnClick(new ActionBuilders.LoadAction.Builder().build()).build();
    }

    static synchronized TileCommand consume(Context context, String id) {
        SharedPreferences prefs = context.getSharedPreferences(TileStore.PREFS, Context.MODE_PRIVATE);
        String address = TileStore.deviceAddress(context), selected = AppPrefs.deviceAddress(context);
        if (!selected.isEmpty() && !selected.equals(address)) return null;
        long consumed = prefs.getLong("tile_action_consumed", 0L);
        int mask = prefs.getInt("tile_action_mask", 0);
        TileCommand command = TileCommand.parse(id, address, prefs.getLong("tile_action_issued", 0L), consumed, mask);
        if (command == null) return null;
        int nextMask = (command.token == consumed ? mask : 0) | (1 << (command.mode + 1));
        prefs.edit().putLong("tile_action_consumed", command.token).putInt("tile_action_mask", nextMask).apply();
        return command;
    }
}

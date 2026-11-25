package com.hdz.wearcontrol;

import android.content.Context;
import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ModifiersBuilders;

/** LoadAction 在磁贴内交互；只有“打开控制”启动 Activity。 */
final class TileActions {
    private final String address;
    private final long token;

    TileActions(Context context) {
        address = TileStore.deviceAddress(context);
        token = TileLedger.issue(context);
    }

    ModifiersBuilders.Clickable mode(int mode) {
        return new ModifiersBuilders.Clickable.Builder().setId(TileCommand.id(token, mode, address))
                .setOnClick(new ActionBuilders.LoadAction.Builder().build()).build();
    }

}

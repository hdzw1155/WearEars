package com.hdz.wearcontrol;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Presents confirmed local cache values when the Android tile becomes visible. */
public final class QuickSettingsTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;

        Context localized = AppLocale.localizedContext(this);
        TileSnapshot snapshot = TileStore.readForQuickSettings(localized);
        boolean connected = snapshot.connection == LinkState.CONNECTED;
        tile.setLabel(localized.getString(R.string.qs_tile_label));
        tile.setSubtitle(connected ? connectedSubtitle(localized, snapshot)
                : localized.getString(R.string.status_idle));
        tile.setState(connected ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private static String connectedSubtitle(Context context, TileSnapshot snapshot) {
        int modeLabel;
        switch (snapshot.confirmedMode) {
            case Controls.MODE_ANC: modeLabel = R.string.tile_mode_anc; break;
            case Controls.MODE_OFF: modeLabel = R.string.btn_off; break;
            case Controls.MODE_TRANS: modeLabel = R.string.btn_trans; break;
            default: modeLabel = 0; break;
        }
        int battery = snapshot.battTotal;
        if (!snapshot.isSingleBattery()) {
            battery = snapshot.battL < 0 || snapshot.battR < 0
                    ? Math.max(snapshot.battL, snapshot.battR)
                    : Math.min(snapshot.battL, snapshot.battR);
        }
        if (battery < 0) {
            return context.getString(modeLabel != 0 ? modeLabel : R.string.status_connected);
        }
        String percent = context.getString(R.string.qs_tile_battery, battery);
        return modeLabel == 0 ? percent
                : context.getString(R.string.qs_tile_mode_battery, context.getString(modeLabel), percent);
    }

    @Override
    @SuppressWarnings("deprecation") // The Intent overload is required through API 33.
    public void onClick() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent launch = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(launch);
        } else {
            startActivityAndCollapse(open);
        }
    }
}

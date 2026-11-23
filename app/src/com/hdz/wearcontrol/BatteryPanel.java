package com.hdz.wearcontrol;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

/** The three Neo styles share a battery canvas. The Suprematist dial owns its battery sectors. */
final class BatteryPanel implements MainPanel {
    private final NeoBatteryView neo;
    private final SuprematistDialView dial;

    BatteryPanel(Activity activity, Actions actions) {
        neo = activity.findViewById(R.id.neo_battery);
        dial = activity.findViewById(R.id.sup_dial);
        if (dial != null) dial.enablePanel(Controls.CAP_BATTERY);
        if (neo == null) return;
        int style = AppPrefs.uiStyle(activity);
        View card = activity.findViewById(R.id.neo_battery_card);
        if (style == StyleChoice.MINIMAL) card.setPadding(0, 0, 0, 0);
        if (style == StyleChoice.CLASSIC) {
            activity.findViewById(R.id.battery_header).setVisibility(View.GONE);
            activity.findViewById(R.id.neo_classic_header).setVisibility(View.VISIBLE);
            float density = activity.getResources().getDisplayMetrics().density;
            int sides = Math.round(14 * density), ends = Math.round(12 * density);
            card.setPadding(sides, ends, sides, ends);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) neo.getLayoutParams();
            lp.topMargin = Math.round(6 * density); neo.setLayoutParams(lp);
        }
    }
    @Override public void render(DeviceState state, int pendingMode) {
        boolean single = (state.potentialCaps & Controls.CAP_BATTERY_SINGLE) != 0;
        int l = state.connected ? state.left : -1, r = state.connected ? state.right : -1;
        int box = state.connected ? state.box : -1, flags = state.connected && state.charge >= 0 ? state.charge : 0;
        if (neo != null) neo.setBattery(l, r, box, flags, single);
        if (dial != null) dial.setBattery(l, r, box, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, single);
    }
}

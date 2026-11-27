package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.DesktopCoreBridge.StateSnapshotView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Remembers which components have already warned during the current discharge. */
final class LowBatteryAlertTracker {
    static final int LOW_THRESHOLD = 20;
    static final int REARM_THRESHOLD = 30;

    private final boolean[] alerted = new boolean[3];
    private String address;

    static final class Reading {
        final int component;
        final int percent;

        Reading(int component, int percent) {
            this.component = component;
            this.percent = percent;
        }
    }

    List<Reading> update(StateSnapshotView state, boolean canNotify) {
        if (!state.connected) {
            if (address != null) Arrays.fill(alerted, false);
            address = null;
            return List.of();
        }
        if (!state.address.equals(address)) {
            address = state.address;
            Arrays.fill(alerted, false);
        }

        boolean single = (state.caps & Controls.CAP_BATTERY_SINGLE) != 0;
        List<Reading> readings = null;
        for (int part = 0; part < (single ? 1 : alerted.length); part++) {
            int percent = part == 0 ? state.left : part == 1 ? state.right : state.box;
            boolean known = DesktopStrings.validBattery(percent);
            if (DesktopStrings.charging(state.charge, part) || (known && percent >= REARM_THRESHOLD)) {
                alerted[part] = false;
            } else if (canNotify && known && percent <= LOW_THRESHOLD && !alerted[part]) {
                alerted[part] = true;
                if (readings == null) readings = new ArrayList<>(alerted.length);
                readings.add(new Reading(part, percent));
            }
        }
        return readings == null ? List.of() : List.copyOf(readings);
    }
}

package com.hdz.wearcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;

import java.util.Locale;

/**
 * Same-device local software bridge for automation tools (Tasker / MacroDroid / Bluetooth ring helpers).
 * Enforces triple safety gates: disclaimer consent, explicit opt-in switch, and strict action allowlist
 * (never handles FIND_MY_BUDS or volume-up actions).
 */
public class LocalControlReceiver extends BroadcastReceiver {
    public static final String ACTION_SET_NOISE = "com.hdz.wearcontrol.ACTION_SET_NOISE";
    public static final String ACTION_CYCLE_NOISE = "com.hdz.wearcontrol.ACTION_CYCLE_NOISE";
    public static final String ACTION_REFRESH = "com.hdz.wearcontrol.ACTION_REFRESH";
    public static final String EXTRA_MODE = "mode";

    /** Synced by SettingsActivity / AppPrefs; defaults to disabled. */
    static volatile boolean bridgeEnabled = false;

    public static void setBridgeEnabled(boolean enabled) {
        bridgeEnabled = enabled;
    }

    public static boolean isBridgeEnabled() {
        return bridgeEnabled;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        // Gate 1: User must have accepted the current disclaimer.
        if (!AppPrefs.hasDisclaimer(context)) return;

        // Gate 2: Local software bridge must be explicitly enabled.
        if (!bridgeEnabled) return;

        // Gate 3: Strict allowlist of noise/refresh actions only (never FIND_MY_BUDS or volume increase).
        ControlHub hub = ControlHub.get(context);
        if (ACTION_SET_NOISE.equals(action)) {
            int mode = parseModeExtra(intent);
            if (mode < Controls.MODE_ANC || mode > Controls.MODE_TRANS) return;
            runOnMain(hub, () -> hub.perform(mode));
        } else if (ACTION_CYCLE_NOISE.equals(action)) {
            runOnMain(hub, () -> hub.perform(nextNoiseMode(hub.mode())));
        } else if (ACTION_REFRESH.equals(action)) {
            runOnMain(hub, () -> hub.perform(-1));
        }
    }

    static int parseModeExtra(Intent intent) {
        if (intent == null) return -1;
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey(EXTRA_MODE)) return -1;
        Object raw = extras.get(EXTRA_MODE);
        if (raw instanceof Number) {
            int val = ((Number) raw).intValue();
            return (val >= Controls.MODE_ANC && val <= Controls.MODE_TRANS) ? val : -1;
        }
        if (raw instanceof String) {
            String token = ((String) raw).trim().toLowerCase(Locale.ROOT);
            if ("anc".equals(token) || "0".equals(token)) return Controls.MODE_ANC;
            if ("off".equals(token) || "1".equals(token)) return Controls.MODE_OFF;
            if ("trans".equals(token) || "2".equals(token)) return Controls.MODE_TRANS;
        }
        return -1;
    }

    static int nextNoiseMode(int current) {
        if (current < Controls.MODE_ANC || current > Controls.MODE_TRANS) {
            return Controls.MODE_ANC;
        }
        return (current + 1) % 3;
    }

    private static void runOnMain(ControlHub hub, Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            hub.main.post(action);
        }
    }
}

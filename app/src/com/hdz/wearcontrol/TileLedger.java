package com.hdz.wearcontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable click consumption before dispatch, including locked versions and out-of-order profile replies. */
final class TileLedger {
    private static final String ISSUED = "tile_action_issued", USED = "tile_action_consumed",
            MASK = "tile_action_mask", SERIAL = "tile_action_sequence";
    static final class Click {
        final TileCommand command;
        final long sequence;
        Click(TileCommand command, long sequence) { this.command = command; this.sequence = sequence; }
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(AppPrefs.STORE, Context.MODE_PRIVATE); }
    static synchronized long issue(Context c) {
        if (!AppPrefs.hasDisclaimer(c)) return 0;
        SharedPreferences p = prefs(c);
        long next = p.getLong(ISSUED, 0) + 1;
        return next > 0 && p.edit().putLong(ISSUED, next).commit() ? next : 0;
    }
    static synchronized Click consume(Context c, String id) {
        SharedPreferences p = prefs(c);
        String address = AppPrefs.snapshotAddress(c), pin = AppPrefs.deviceAddress(c);
        long used = p.getLong(USED, 0);
        int mask = p.getInt(MASK, 0);
        TileCommand cmd = TileCommand.parse(id, address, p.getLong(ISSUED, 0), used, mask);
        if (cmd == null) return null;
        long sequence = p.getLong(SERIAL, 0) + 1;
        boolean persisted = p.edit().putLong(USED, cmd.token).putInt(MASK, (cmd.token == used ? mask : 0) | 1 << (cmd.mode + 1))
                .putLong(SERIAL, sequence).commit();
        return persisted && AppPrefs.hasDisclaimer(c) && (pin.isEmpty() || pin.equals(address)) ? new Click(cmd, sequence) : null;
    }
    static synchronized boolean current(Context c, Click click) {
        return AppPrefs.hasDisclaimer(c) && prefs(c).getLong(SERIAL, 0) == click.sequence;
    }
    static synchronized void invalidate(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putLong(SERIAL, p.getLong(SERIAL, 0) + 1).apply();
    }
    static synchronized void accept(SharedPreferences p, String key, int version) {
        // All renders minted before consent, including IDs the host has not delivered yet, are spent.
        if (!p.edit().putLong(USED, p.getLong(ISSUED, 0)).putInt(MASK, 15)
                .putLong(SERIAL, p.getLong(SERIAL, 0) + 1).putInt(key, version).commit()) {
            p.edit().remove(key).apply();
        }
    }
    private TileLedger() { }
}

package com.hdz.wearcontrol;

import android.content.SharedPreferences;
import java.util.*;

/** Shared instance models application-private preferences across separately created entry points. */
final class MemorySettings implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    boolean rejectDiskWrite;
    public Map<String, ?> getAll() { return new HashMap<>(values); }
    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) { return (int) values.getOrDefault(key, fallback); }
    public long getLong(String key, long fallback) { return (long) values.getOrDefault(key, fallback); }
    public boolean getBoolean(String key, boolean fallback) { return (boolean) values.getOrDefault(key, fallback); }
    public float getFloat(String key, float fallback) { return (float) values.getOrDefault(key, fallback); }
    public Set<String> getStringSet(String key, Set<String> fallback) { return fallback; }
    public boolean contains(String key) { return values.containsKey(key); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener ignored) { }
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener ignored) { }

    public Editor edit() {
        return new Editor() {
            private final Map<String, Object> updates = new HashMap<>();
            private boolean clear;
            public Editor putString(String k, String v) { updates.put(k, v); return this; }
            public Editor putStringSet(String k, Set<String> v) { updates.put(k, v); return this; }
            public Editor putInt(String k, int v) { updates.put(k, v); return this; }
            public Editor putLong(String k, long v) { updates.put(k, v); return this; }
            public Editor putBoolean(String k, boolean v) { updates.put(k, v); return this; }
            public Editor putFloat(String k, float v) { updates.put(k, v); return this; }
            public Editor remove(String k) { updates.put(k, null); return this; }
            public Editor clear() { clear = true; return this; }
            public void apply() {
                if (clear) values.clear();
                updates.forEach((key, value) -> {
                    if (value == null) values.remove(key); else values.put(key, value);
                });
            }
            public boolean commit() { apply(); return !rejectDiskWrite; }
        };
    }
}

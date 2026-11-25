package com.hdz.wearcontrol;

/** Immutable option entry for CHOICE/ACTION_TARGET features, preserving protocol wire IDs. */
public final class OptionItem {
    public static final int ROLE_DEFAULT = 0;
    public static final int ROLE_WIND = 1;
    public static final int ROLE_VOICE = 2;
    public static final int ROLE_ADAPTIVE = 3;

    public final int id;
    public final String chinese;
    public final String english;
    public final int role;

    public OptionItem(int id, String chinese, String english) {
        this(id, chinese, english, ROLE_DEFAULT);
    }

    public OptionItem(int id, String chinese, String english, int role) {
        this.id = id;
        this.chinese = chinese == null ? "" : chinese;
        this.english = english == null ? "" : english;
        this.role = role;
    }

    public String label(boolean useEnglish) {
        if (useEnglish && !english.isEmpty()) return english;
        return chinese.isEmpty() ? english : chinese;
    }

    /** Converts legacy SoundPreset entries into OptionItems with ROLE_DEFAULT. */
    public static OptionItem[] fromPresets(SoundPreset[] presets) {
        if (presets == null || presets.length == 0) return new OptionItem[0];
        int count = 0;
        for (SoundPreset preset : presets) if (preset != null) count++;
        OptionItem[] items = new OptionItem[count];
        int index = 0;
        for (SoundPreset preset : presets) {
            if (preset != null) {
                items[index++] = new OptionItem(preset.id, preset.chinese, preset.english, ROLE_DEFAULT);
            }
        }
        return items;
    }
}

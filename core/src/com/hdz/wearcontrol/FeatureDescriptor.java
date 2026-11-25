package com.hdz.wearcontrol;

/** Immutable runtime capability descriptor with per-feature verification, safety, and mode constraints. */
public final class FeatureDescriptor {
    public enum Kind { TOGGLE, CHOICE, READ_ONLY, ACTION_TARGET, SLIDER }

    public static final int SAFETY_NONE = 0;
    public static final int SAFETY_AUDIO_WARN = 1;
    public static final int SAFETY_FIND_BUDS = 2;

    public final FeatureId id;
    public final Kind kind;
    public final int verif;
    public final int safety;
    public final int parentModeMask;
    public final boolean devOnly;
    public final OptionItem[] options;
    public final int minValue;
    public final int maxValue;
    public final String limitZh;
    public final String limitEn;

    public FeatureDescriptor(FeatureId id, Kind kind, int verif, int safety, int parentModeMask,
                             boolean devOnly, OptionItem[] options, int minValue, int maxValue,
                             String limitZh, String limitEn) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        this.id = id;
        this.kind = kind;
        this.verif = verif;
        this.safety = safety;
        this.parentModeMask = parentModeMask;
        this.devOnly = devOnly;
        this.options = copyOptions(options);
        this.minValue = Math.min(minValue, maxValue);
        this.maxValue = Math.max(minValue, maxValue);
        this.limitZh = limitZh == null ? "" : limitZh;
        this.limitEn = limitEn == null ? "" : limitEn;
    }

    private static OptionItem[] copyOptions(OptionItem[] source) {
        if (source == null || source.length == 0) return new OptionItem[0];
        int count = 0;
        for (OptionItem item : source) if (item != null) count++;
        OptionItem[] copy = new OptionItem[count];
        int idx = 0;
        for (OptionItem item : source) if (item != null) copy[idx++] = item;
        return copy;
    }

    public static FeatureDescriptor toggle(FeatureId id, int verif, boolean devOnly) {
        return toggle(id, verif, SAFETY_NONE, 0, devOnly, "", "");
    }

    public static FeatureDescriptor toggle(FeatureId id, int verif, int safety, int parentModeMask,
                                           boolean devOnly) {
        return toggle(id, verif, safety, parentModeMask, devOnly, "", "");
    }

    public static FeatureDescriptor toggle(FeatureId id, int verif, int safety, int parentModeMask,
                                           boolean devOnly, String limitZh, String limitEn) {
        return new FeatureDescriptor(id, Kind.TOGGLE, verif, safety, parentModeMask, devOnly,
                new OptionItem[0], 0, 1, limitZh, limitEn);
    }

    public static FeatureDescriptor choice(FeatureId id, int verif, boolean devOnly,
                                           OptionItem... options) {
        return choice(id, verif, SAFETY_NONE, 0, devOnly, options, "", "");
    }

    public static FeatureDescriptor choice(FeatureId id, int verif, int safety, int parentModeMask,
                                           boolean devOnly, OptionItem... options) {
        return choice(id, verif, safety, parentModeMask, devOnly, options, "", "");
    }

    public static FeatureDescriptor choice(FeatureId id, int verif, int safety, int parentModeMask,
                                           boolean devOnly, OptionItem[] options,
                                           String limitZh, String limitEn) {
        return new FeatureDescriptor(id, Kind.CHOICE, verif, safety, parentModeMask, devOnly,
                options, 0, 0, limitZh, limitEn);
    }

    public static FeatureDescriptor slider(FeatureId id, int verif, int safety, int parentModeMask,
                                           boolean devOnly, int minValue, int maxValue) {
        return slider(id, verif, safety, parentModeMask, devOnly, minValue, maxValue, "", "");
    }

    public static FeatureDescriptor slider(FeatureId id, int verif, int safety, int parentModeMask,
                                           boolean devOnly, int minValue, int maxValue,
                                           String limitZh, String limitEn) {
        return new FeatureDescriptor(id, Kind.SLIDER, verif, safety, parentModeMask, devOnly,
                new OptionItem[0], minValue, maxValue, limitZh, limitEn);
    }

    public static FeatureDescriptor readOnly(FeatureId id, int verif, boolean devOnly) {
        return readOnly(id, verif, devOnly, "", "");
    }

    public static FeatureDescriptor readOnly(FeatureId id, int verif, boolean devOnly,
                                             String limitZh, String limitEn) {
        return new FeatureDescriptor(id, Kind.READ_ONLY, verif, SAFETY_NONE, 0, devOnly,
                new OptionItem[0], 0, 0, limitZh, limitEn);
    }

    public static FeatureDescriptor findBuds(int verif, boolean devOnly) {
        return findBuds(verif, devOnly, new OptionItem[0], "", "");
    }

    public static FeatureDescriptor findBuds(int verif, boolean devOnly,
                                             String limitZh, String limitEn) {
        return findBuds(verif, devOnly, new OptionItem[0], limitZh, limitEn);
    }

    public static FeatureDescriptor findBuds(int verif, boolean devOnly, OptionItem[] options,
                                             String limitZh, String limitEn) {
        return new FeatureDescriptor(FeatureId.FIND_MY_BUDS, Kind.ACTION_TARGET, verif,
                SAFETY_FIND_BUDS, 0, devOnly, options, 0, 3, limitZh, limitEn);
    }

    public OptionItem findOption(int value) {
        for (OptionItem option : options) {
            if (option.id == value) return option;
        }
        return null;
    }

    public boolean allowsNoiseMode(int noiseMode) {
        if (parentModeMask == 0) return true;
        return noiseMode >= Controls.MODE_ANC && noiseMode <= Controls.MODE_TRANS
                && (parentModeMask & (1 << noiseMode)) != 0;
    }

    public boolean isValidValue(int value) {
        switch (kind) {
            case TOGGLE:
                return value == 0 || value == 1;
            case CHOICE:
                return findOption(value) != null;
            case SLIDER:
                return value >= minValue && value <= maxValue;
            case ACTION_TARGET:
                if (options.length > 0) {
                    return (id == FeatureId.FIND_MY_BUDS && value == 0) || findOption(value) != null;
                }
                return value >= minValue && value <= maxValue;
            case READ_ONLY:
            default:
                return false;
        }
    }

    public String limit(boolean useEnglish) {
        if (useEnglish && !limitEn.isEmpty()) return limitEn;
        return limitZh.isEmpty() ? limitEn : limitZh;
    }
}

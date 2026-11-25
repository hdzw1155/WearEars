package com.hdz.wearcontrol.huawei;

import com.hdz.wearcontrol.SoundPreset;
import java.util.ArrayList;
import java.util.List;

/** OpenFreebuds 的 2B/4A 查询与 2B/49 内置预设协议，保留厂商 TLV 原值。 */
public final class HuaweiSoundProtocol {
    private HuaweiSoundProtocol() { }

    public static boolean supportsQuery(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("honor") || name.contains("荣耀")) return false;
        String model = lower.replaceAll("[^a-z0-9]", "");
        return model.matches("(huawei)?freebuds(5i|6i|se4anc|pro2|pro3|pro4)");
    }

    public static boolean usesPro3PresetMap(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("honor") || name.contains("荣耀")) return false;
        String model = lower.replaceAll("[^a-z0-9]", "");
        return model.matches("(huawei)?freebudspro[34]");
    }

    public static final class State {
        public final int current;
        public final SoundPreset[] presets;
        State(int current, SoundPreset[] presets) { this.current = current; this.presets = presets; }
    }

    public static State decode(byte[] bytes, int offset, int length) {
        return decode(bytes, offset, length, null);
    }

    public static State decode(byte[] bytes, int offset, int length, String name) {
        if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length) return null;
        boolean pro3Map = usesPro3PresetMap(name);
        int current = -1, end = offset + length;
        List<SoundPreset> presets = new ArrayList<SoundPreset>();
        boolean modeSeen = false, optionsSeen = false;
        while (offset < end) {
            if (end - offset < 2) return null;
            int tag = bytes[offset++] & 255, size = bytes[offset++] & 255;
            if (size > end - offset) return null;
            if (tag == 2) {
                if (modeSeen || size != 1) return null;
                current = bytes[offset] & 255;
                modeSeen = true;
            } else if (tag == 3) {
                if (optionsSeen) return null;
                optionsSeen = true;
                for (int i = 0; i < size; i++) {
                    SoundPreset preset = preset(bytes[offset + i] & 255, pro3Map);
                    if (preset != null) {
                        boolean duplicate = false;
                        for (SoundPreset previous : presets) if (previous.id == preset.id) duplicate = true;
                        if (!duplicate) presets.add(preset);
                    }
                }
            }
            offset += size;
        }
        if (!modeSeen || !optionsSeen || presets.isEmpty()) return null;
        return new State(current, presets.toArray(new SoundPreset[presets.size()]));
    }

    private static SoundPreset preset(int id, boolean pro3Map) {
        if (pro3Map) {
            switch (id) {
                case 5: return new SoundPreset(5, "默认", "Default");
                case 1: return new SoundPreset(1, "低音", "Bass");
                case 2: return new SoundPreset(2, "高音", "Treble");
                case 9: return new SoundPreset(9, "人声", "Vocals");
                default: return null;
            }
        }
        switch (id) {
            case 1: return new SoundPreset(1, "默认", "Default");
            case 2: return new SoundPreset(2, "低音", "Bass");
            case 3: return new SoundPreset(3, "高音", "Treble");
            case 9: return new SoundPreset(9, "人声", "Vocals");
            default: return null;
        }
    }
}

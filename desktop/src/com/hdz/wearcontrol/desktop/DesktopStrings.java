package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.DesktopCoreBridge;
import com.hdz.wearcontrol.DesktopCoreBridge.StateSnapshotView;
import com.hdz.wearcontrol.FeatureId;

final class DesktopStrings {
    final boolean english;

    DesktopStrings(String language) {
        english = "en".equals(language);
    }

    String text(String zh, String en) {
        return english ? en : zh;
    }

    String noise(int mode) {
        switch (mode) {
            case Controls.MODE_ANC: return text("降噪", "ANC");
            case Controls.MODE_TRANS: return text("通透", "Transparency");
            case Controls.MODE_OFF: return text("关闭", "Off");
            default: return "--";
        }
    }

    String wear(int value) {
        switch (value) {
            case DesktopCoreBridge.WEAR_LEFT: return text("仅左耳入耳", "Left bud in ear");
            case DesktopCoreBridge.WEAR_RIGHT: return text("仅右耳入耳", "Right bud in ear");
            case DesktopCoreBridge.WEAR_BOTH: return text("双耳入耳", "Both buds in ear");
            case DesktopCoreBridge.WEAR_NONE: return text("未佩戴", "Not worn");
            default: return text("佩戴未知", "Wear status unknown");
        }
    }

    String brand(String key, String fallback) {
        if (!english) return fallback;
        switch (key) {
            case "vivo": return "vivo / iQOO";
            case "huawei": return "Huawei / Honor";
            case "xiaomi": return "Xiaomi / Redmi";
            case "oppo": return "OPPO / OnePlus / realme";
            case "edifier": return "Edifier";
            case "rose": return "Rose Technics";
            case "nicehck": return "NiceHCK";
            case "sony": return "Sony";
            case "technics": return "Technics";
            default: return fallback;
        }
    }

    String feature(FeatureId id) {
        switch (id) {
            case ANC_LEVEL: return text("细分降噪档位", "ANC level");
            case WIND_REDUCTION: return text("抗风噪", "Wind reduction");
            case TRANS_MODE: return text("通透模式", "Transparency mode");
            case EQUALIZER: return text("内置音效 / DeepX", "Sound presets / DeepX");
            case SPATIAL_AUDIO: return text("耳机硬件空间音频", "Headphone spatial audio");
            case LOW_LATENCY: return text("游戏低延迟", "Low-latency gaming");
            case MULTIPOINT: return text("双设备连接", "Multipoint");
            case WEAR_DETECTION: return text("入耳佩戴检测", "Wear detection");
            case FIND_MY_BUDS: return text("查找耳机", "Find My Buds");
            default: throw new IllegalArgumentException(id.name());
        }
    }

    String experimental() {
        return text(" [实验]", " [Experimental]");
    }

    static String percent(int value) {
        return validBattery(value) ? value + "%" : "--";
    }

    static boolean validBattery(int value) {
        return value >= 0 && value <= 100;
    }

    static boolean charging(int flags, int index) {
        return flags >= 0 && (flags & (1 << index)) != 0;
    }

    String batteryLabel(boolean single, int component, boolean compact) {
        if (single) return text("总电量", "TOTAL");
        if (component == 0) return compact ? "L" : text("左耳", "Left");
        if (component == 1) return compact ? "R" : text("右耳", "Right");
        return text(compact ? "盒" : "充电盒", compact ? "Box" : "Charging case");
    }

    String batterySummary(int caps, int left, int right, int box, int charge) {
        boolean single = (caps & Controls.CAP_BATTERY_SINGLE) != 0;
        String result = batteryLabel(single, 0, true) + " " + percent(left) + bolt(charge, 0);
        if (single) return result;
        return result + "  R " + percent(right) + bolt(charge, 1)
                + "  " + batteryLabel(false, 2, true) + " " + percent(box) + bolt(charge, 2);
    }

    private static String bolt(int charge, int index) {
        return charging(charge, index) ? " ⚡" : "";
    }

    String tooltip(DesktopSession.ConnectionPhase phase, StateSnapshotView state) {
        if (!state.connected) {
            return phase == DesktopSession.ConnectionPhase.CONNECTING
                    ? text("WearEars · 正在连接", "WearEars · Connecting") + " " + truncate(state.name, 75)
                    : text("WearEars · 未连接（点击打开）", "WearEars · Disconnected (click to open)");
        }
        String details = batterySummary(state.caps, state.left, state.right, state.box, state.charge)
                + " · " + noise(state.mode);
        String label = "WearEars · ";
        String name = state.name.isBlank() ? text("耳机已连接", "Headphones connected") : state.name;
        return label + truncate(name, 127 - label.length() - 1 - details.length()) + "\n" + details;
    }

    private static String truncate(String name, int length) {
        String safe = name.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
        if (safe.length() <= length) return safe;
        int end = Math.max(0, length - 1);
        if (end > 0 && Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        return safe.substring(0, end) + "…";
    }
}

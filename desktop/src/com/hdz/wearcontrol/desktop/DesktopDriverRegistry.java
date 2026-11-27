package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.DesktopCoreBridge;
import com.hdz.wearcontrol.DesktopCoreBridge.BrandModuleEntry;
import com.hdz.wearcontrol.WireDriver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry auto-assembling all 9 brand DriverModules from drivers/.
 * Supports automatic matching by Bluetooth device name plus manual brand override fallback.
 */
public final class DesktopDriverRegistry {
    public static final String BRAND_AUTO = "auto";

    private final List<BrandModuleEntry> entries;
    private final Map<String, BrandModuleEntry> byKey;

    public DesktopDriverRegistry() {
        BrandModuleEntry[] raw = DesktopCoreBridge.allBrandModules();
        this.entries = Collections.unmodifiableList(Arrays.asList(raw));
        Map<String, BrandModuleEntry> map = new LinkedHashMap<>();
        for (BrandModuleEntry entry : raw) {
            map.put(entry.key.toLowerCase(Locale.ROOT), entry);
        }
        this.byKey = Collections.unmodifiableMap(map);
    }

    public List<BrandModuleEntry> allBrands() {
        return entries;
    }

    public BrandModuleEntry findByKey(String key) {
        if (key == null) return null;
        return byKey.get(key.trim().toLowerCase(Locale.ROOT));
    }

    public BrandModuleEntry matchByName(String deviceName) {
        if (deviceName == null || deviceName.trim().isEmpty()) return null;
        for (BrandModuleEntry entry : entries) {
            if (entry.module.matches(deviceName)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Resolves a brand entry using manual override first, otherwise matching by device name.
     */
    public BrandModuleEntry resolve(String deviceName, String forcedBrandKey) {
        if (forcedBrandKey != null && !forcedBrandKey.trim().isEmpty()
                && !BRAND_AUTO.equalsIgnoreCase(forcedBrandKey.trim())) {
            BrandModuleEntry manual = findByKey(forcedBrandKey);
            if (manual != null) return manual;
        }
        return matchByName(deviceName);
    }

    public WireDriver createDriver(String deviceName, String forcedBrandKey) {
        BrandModuleEntry entry = resolve(deviceName, forcedBrandKey);
        return entry == null ? null : entry.module.create();
    }

    /**
     * Scores a discovered Windows Bluetooth device so compatible TWS headsets always
     * float to the top and get auto-selected ahead of phones, PCs, watches, mice, or keyboards.
     */
    public int scoreDevice(WindowsBluetoothBridge.BtDevice dev, String lastMac) {
        if (dev == null) return Integer.MIN_VALUE;
        String name = dev.name == null ? "" : dev.name.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        int score = 0;

        boolean hasAudioKeyword = hasAudioKeyword(lower);
        boolean looksLikeNonAudio = looksLikeNonAudio(lower, hasAudioKeyword);

        boolean brandMatched = !looksLikeNonAudio && matchByName(name) != null;
        if (brandMatched) {
            score += 1000;
        }
        if (matchesMac(dev, lastMac)) {
            score += 260;
        }
        if (dev.connected) {
            score += 120;
        }
        if (hasAudioKeyword) {
            score += 180;
        }
        if (looksLikeNonAudio) {
            score -= 400;
        }
        return score;
    }

    private static boolean hasAudioKeyword(String lower) {
        return containsAny(lower, "tws", "buds", "airpods", "freebuds", "enco",
                "earphone", "earbud", "headphone", "headset", "linkbuds", "wf-", "wh-", "neobuds",
                "eah-", "ceramics", "orig", "耳机", "耳塞", "头戴", "音箱");
    }

    private static boolean looksLikeNonAudio(String lower, boolean hasAudioKeyword) {
        return !hasAudioKeyword && containsAny(lower,
                "iqoo", "iphone", "galaxy s", "galaxy z", "pixel",
                "xiaomi 1", "redmi k", "mate ", "pura ", "find x", "vivo x", "vivo s", "vivo y",
                "oneplus ", "phone", "手机",
                "watch", "band", "手表", "手环", "mouse", "keyboard", "鼠标", "键盘",
                "desktop-", "laptop-", "macbook", "ipad", "tablet", "平板", "controller", "gamepad", "手柄");

    }

    static boolean matchesMac(WindowsBluetoothBridge.BtDevice dev, String mac) {
        return dev != null && mac != null && !mac.isBlank() && dev.mac.equalsIgnoreCase(mac.trim());
    }

    /** Returns a new list ordered by headphone suitability (best match first). */
    public List<WindowsBluetoothBridge.BtDevice> rankDevices(
            List<WindowsBluetoothBridge.BtDevice> devices, String lastMac) {
        if (devices == null || devices.isEmpty()) return Collections.emptyList();
        List<WindowsBluetoothBridge.BtDevice> ranked = new ArrayList<>(devices);
        ranked.removeIf(java.util.Objects::isNull);
        ranked.sort((a, b) -> {
            int sa = scoreDevice(a, lastMac);
            int sb = scoreDevice(b, lastMac);
            if (sa != sb) return Integer.compare(sb, sa);
            if (a.connected != b.connected) return a.connected ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return ranked;
    }

    /** Picks the highest ranked connected headphone or an explicitly remembered device. */
    public WindowsBluetoothBridge.BtDevice findAutoConnectCandidate(
            List<WindowsBluetoothBridge.BtDevice> ranked, String lastMac) {
        if (ranked == null) return null;
        for (WindowsBluetoothBridge.BtDevice device : ranked) {
            if (device == null || !device.connected) continue;
            if (matchesMac(device, lastMac)) return device;
            String lower = device.name.toLowerCase(Locale.ROOT);
            if (!looksLikeNonAudio(lower, hasAudioKeyword(lower)) && matchByName(device.name) != null) return device;
        }
        return null;
    }

    WindowsBluetoothBridge.BtDevice findReconnectCandidate(
            List<WindowsBluetoothBridge.BtDevice> ranked, String lastMac, String lastBrand) {
        for (WindowsBluetoothBridge.BtDevice device : ranked) {
            if (matchesMac(device, lastMac) && device.connected && resolve(device.name, lastBrand) != null) {
                return device;
            }
        }
        return findAutoConnectCandidate(ranked, "");
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}

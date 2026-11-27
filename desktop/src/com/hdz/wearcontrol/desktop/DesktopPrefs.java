package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.LocalMeshFirewall;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/**
 * Lightweight user preference store in %USERPROFILE%\.wearears\desktop.properties.
 * Never hardcodes drive letters; gracefully falls back to in-memory if filesystem is read-only.
 */
public final class DesktopPrefs {
    private static final String KEY_DISCLAIMER = "disclaimer_accepted";
    private static final String KEY_EXP_CONSENT = "exp_feature_consent";
    private static final String KEY_LAST_BRAND = "last_brand";
    private static final String KEY_LAST_MAC = "last_mac";
    private static final String KEY_FW_ENABLED = "fw_enabled";
    private static final String KEY_FW_SUBNET = "fw_same_subnet";
    private static final String KEY_FW_ALLOWLIST_ONLY = "fw_allowlist_only";
    private static final String KEY_FW_READ_ONLY = "fw_read_only";
    private static final String KEY_FW_ALLOWLIST = "fw_allowlist";
    private static final String KEY_MESH_PIN = "mesh_pin";
    private static final String KEY_UI_STYLE = "ui_style";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_AUTO_CONNECT = "auto_connect";
    private static final String KEY_START_MINIMIZED = "start_minimized";
    private static final String KEY_AUTO_HIDE = "auto_hide_on_blur";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_TRAY_ICON_STYLE = "tray_icon_style";
    private static final String KEY_LOW_BATTERY_ALERT = "low_battery_alert";
    private static final String KEY_GLOBAL_HOTKEYS = "global_hotkeys";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";
    static final String TRAY_HEADSET = "headset";
    static final String TRAY_BATTERY = "battery";

    private final Path file;
    private final Properties props = new Properties();

    public DesktopPrefs() {
        this(userDirectory().resolve("desktop.properties"));
    }

    public DesktopPrefs(Path file) {
        this.file = file == null ? null : file.toAbsolutePath();
        load();
    }

    static Path userDirectory() {
        return Paths.get(System.getProperty("user.home", "."), ".wearears");
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
        }
    }

    public synchronized void save() {
        if (file == null) return;
        Path temporary = null;
        try {
            Path parent = file.getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "desktop-", ".properties");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                props.store(out, "WearEars Desktop Preferences");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | SecurityException ignored) {
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException | SecurityException ignored) { }
            }
        }
    }

    public boolean disclaimerAccepted() {
        return "true".equalsIgnoreCase(props.getProperty(KEY_DISCLAIMER, "false"));
    }

    public void setDisclaimerAccepted(boolean accepted) {
        props.setProperty(KEY_DISCLAIMER, Boolean.toString(accepted));
        save();
    }

    public boolean expFeatureConsent() {
        return "true".equalsIgnoreCase(props.getProperty(KEY_EXP_CONSENT, "false"));
    }

    public void setExpFeatureConsent(boolean accepted) {
        props.setProperty(KEY_EXP_CONSENT, Boolean.toString(accepted));
        save();
    }

    public String lastBrand() {
        return props.getProperty(KEY_LAST_BRAND, DesktopDriverRegistry.BRAND_AUTO);
    }

    public void setLastBrand(String brand) {
        props.setProperty(KEY_LAST_BRAND, brand == null ? DesktopDriverRegistry.BRAND_AUTO : brand);
        save();
    }

    public String lastMac() {
        return props.getProperty(KEY_LAST_MAC, "");
    }

    public void setLastMac(String mac) {
        props.setProperty(KEY_LAST_MAC, mac == null ? "" : mac.trim().toUpperCase(Locale.ROOT));
        save();
    }

    synchronized void rememberConnection(String mac, String brand) {
        props.setProperty(KEY_LAST_MAC, mac == null ? "" : mac.trim().toUpperCase(Locale.ROOT));
        setLastBrand(brand);
    }

    public String uiStyle() {
        return DesktopTheme.forKey(props.getProperty(KEY_UI_STYLE)).key;
    }

    public void setUiStyle(String style) {
        set(KEY_UI_STYLE, DesktopTheme.forKey(style).key);
    }

    public String language() {
        return "en".equals(props.getProperty(KEY_LANGUAGE)) ? "en" : "zh";
    }

    public void setLanguage(String language) {
        set(KEY_LANGUAGE, "en".equals(language) ? "en" : "zh");
    }

    public boolean autoConnect() { return flag(KEY_AUTO_CONNECT, true); }
    public boolean startMinimized() { return flag(KEY_START_MINIMIZED, false); }
    public boolean autoHideOnBlur() { return flag(KEY_AUTO_HIDE, true); }
    public boolean pinned() { return flag(KEY_PINNED, false); }
    public String trayIconStyle() { return trayStyle(props.getProperty(KEY_TRAY_ICON_STYLE)); }
    public boolean lowBatteryAlert() { return flag(KEY_LOW_BATTERY_ALERT, true); }
    public boolean globalHotkeys() { return flag(KEY_GLOBAL_HOTKEYS, false); }
    public boolean autoReconnect() { return flag(KEY_AUTO_RECONNECT, true); }

    public void setAutoConnect(boolean value) { set(KEY_AUTO_CONNECT, Boolean.toString(value)); }
    public void setStartMinimized(boolean value) { set(KEY_START_MINIMIZED, Boolean.toString(value)); }
    public void setAutoHideOnBlur(boolean value) { set(KEY_AUTO_HIDE, Boolean.toString(value)); }
    public void setPinned(boolean value) { set(KEY_PINNED, Boolean.toString(value)); }
    public void setTrayIconStyle(String style) { set(KEY_TRAY_ICON_STYLE, trayStyle(style)); }
    public void setLowBatteryAlert(boolean value) { set(KEY_LOW_BATTERY_ALERT, Boolean.toString(value)); }
    public void setGlobalHotkeys(boolean value) { set(KEY_GLOBAL_HOTKEYS, Boolean.toString(value)); }
    public void setAutoReconnect(boolean value) { set(KEY_AUTO_RECONNECT, Boolean.toString(value)); }

    private static String trayStyle(String style) {
        return TRAY_BATTERY.equals(style) ? TRAY_BATTERY : TRAY_HEADSET;
    }

    private boolean flag(String key, boolean fallback) {
        String value = props.getProperty(key);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    private synchronized void set(String key, String value) {
        props.setProperty(key, value);
        save();
    }

    public String meshPin() {
        return props.getProperty(KEY_MESH_PIN, "");
    }

    public void setMeshPin(String pin) {
        props.setProperty(KEY_MESH_PIN, pin == null ? "" : pin);
        save();
    }

    public void applyToFirewall(LocalMeshFirewall fw) {
        if (fw == null) return;
        boolean enabled = !"false".equalsIgnoreCase(props.getProperty(KEY_FW_ENABLED, "true"));
        boolean subnet = !"false".equalsIgnoreCase(props.getProperty(KEY_FW_SUBNET, "true"));
        boolean allowlistOnly = "true".equalsIgnoreCase(props.getProperty(KEY_FW_ALLOWLIST_ONLY, "false"));
        boolean readOnly = "true".equalsIgnoreCase(props.getProperty(KEY_FW_READ_ONLY, "false"));
        fw.configure(enabled, subnet, allowlistOnly, readOnly);
        fw.setAllowlistFromCsv(props.getProperty(KEY_FW_ALLOWLIST, ""));
    }

    public void saveFirewall(LocalMeshFirewall fw) {
        if (fw == null) return;
        props.setProperty(KEY_FW_ENABLED, Boolean.toString(fw.isEnabled()));
        props.setProperty(KEY_FW_SUBNET, Boolean.toString(fw.isSameSubnetOnly()));
        props.setProperty(KEY_FW_ALLOWLIST_ONLY, Boolean.toString(fw.isAllowlistOnly()));
        props.setProperty(KEY_FW_READ_ONLY, Boolean.toString(fw.isReadOnlyMode()));
        props.setProperty(KEY_FW_ALLOWLIST, fw.allowlistToCsv());
        save();
    }
}

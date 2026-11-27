package com.hdz.wearcontrol;

import java.util.Locale;
import java.util.regex.Pattern;

/** Formats diagnostic metadata that is safe to share outside the app. */
public final class DiagnosticReportBuilder {
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    private static final Pattern SENSITIVE = Pattern.compile(
            "[0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5}"
                    + "|\\b[0-9]{1,3}(?:\\.[0-9]{1,3}){3}\\b"
                    + "|(?<!\\p{Nd})\\p{Nd}{4}(?!\\p{Nd})");
    private static final Pattern VERSION = Pattern.compile(
            "[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(?:-(?:exp(?:-[A-Z])?|alpha|beta|rc)(?:[.-][0-9]{1,3})?)?",
            Pattern.CASE_INSENSITIVE);

    public enum MeshRole { OFF, HOST, CLIENT }

    public static final class Input {
        public final String appVersion;
        public final int androidApi;
        public final String hostFormLabel;
        public final boolean btPermissionGranted;
        public final String driverBrand;
        public final int verifLevel;
        public final String connectionStage;
        public final MeshRole meshRole;
        public final boolean firewallEnabled;
        public final boolean firewallReadOnly;
        public final int firewallBlockedCount;
        public final int firewallBannedCount;
        public final int supportedFeatureCount;
        public final boolean isEnglish;

        public Input(String appVersion, int androidApi, String hostFormLabel, boolean btPermissionGranted,
                     String driverBrand, int verifLevel, String connectionStage, MeshRole meshRole,
                     boolean firewallEnabled, boolean firewallReadOnly, int firewallBlockedCount,
                     int firewallBannedCount, int supportedFeatureCount, boolean isEnglish) {
            this.appVersion = appVersion;
            this.androidApi = androidApi;
            this.hostFormLabel = hostFormLabel;
            this.btPermissionGranted = btPermissionGranted;
            this.driverBrand = driverBrand;
            this.verifLevel = verifLevel;
            this.connectionStage = connectionStage;
            this.meshRole = meshRole;
            this.firewallEnabled = firewallEnabled;
            this.firewallReadOnly = firewallReadOnly;
            this.firewallBlockedCount = firewallBlockedCount;
            this.firewallBannedCount = firewallBannedCount;
            this.supportedFeatureCount = supportedFeatureCount;
            this.isEnglish = isEnglish;
        }
    }

    private DiagnosticReportBuilder() { }

    /** Scrubs short tokens. The report separately validates every text field against known values. */
    public static String sanitizeToken(String raw) {
        if (raw == null || raw.length() > MAX_TOKEN_LENGTH) return "";
        String clean = CONTROLS.matcher(raw).replaceAll("").trim();
        return SENSITIVE.matcher(clean).replaceAll("[redacted]");
    }

    public static String build(Input input) {
        if (input == null) {
            input = new Input(null, -1, null, false, null, -1, null, null, false, false, -1, -1, -1, false);
        }
        boolean en = input.isEnglish;
        String unknown = en ? "Unknown" : "未知";
        String version = sanitizeToken(input.appVersion);
        StringBuilder report = new StringBuilder(en
                ? "WearEars diagnostic report\nRedacted: no MAC addresses, IP addresses, PINs or device nicknames.\n"
                : "WearEars 诊断报告\n已脱敏：不含 MAC、IP、PIN 与设备昵称\n");
        append(report, en ? "App version" : "应用版本", VERSION.matcher(version).matches() ? version : unknown);
        append(report, "Android API", input.androidApi > 0 && input.androidApi < 1000 ? input.androidApi : unknown);
        append(report, en ? "Host form" : "终端形态", hostForm(input.hostFormLabel, en, unknown));
        append(report, en ? "Bluetooth permission" : "蓝牙权限", input.btPermissionGranted
                ? (en ? "Granted" : "已授予") : (en ? "Denied" : "未授予"));
        append(report, en ? "Driver" : "驱动", brand(input.driverBrand, en, unknown));
        append(report, en ? "Verification level" : "验证等级", verification(input.verifLevel, en, unknown));
        append(report, en ? "Connection stage" : "连接阶段", stage(input.connectionStage, en, unknown));
        append(report, en ? "Mesh role" : "局域网协同角色", role(input.meshRole, en, unknown));
        append(report, en ? "Firewall" : "防火墙", toggle(input.firewallEnabled, en));
        append(report, en ? "Read-only mode" : "只读模式", toggle(input.firewallReadOnly, en));
        append(report, en ? "Blocked events" : "拦截次数", count(input.firewallBlockedCount, unknown));
        append(report, en ? "Banned IP count" : "封禁 IP 数量", count(input.firewallBannedCount, unknown));
        append(report, en ? "Supported features" : "支持功能数量", count(input.supportedFeatureCount, unknown));
        return report.toString();
    }

    private static void append(StringBuilder report, String label, Object value) {
        report.append(label).append(" = ").append(value).append('\n');
    }

    private static Object count(int value, String unknown) {
        return value < 0 ? unknown : value;
    }

    private static String toggle(boolean enabled, boolean en) {
        return enabled ? (en ? "On" : "开启") : (en ? "Off" : "关闭");
    }

    private static String hostForm(String raw, boolean en, String unknown) {
        switch (sanitizeToken(raw).toLowerCase(Locale.ROOT)) {
            case "watch": return en ? "Watch" : "手表";
            case "phone": return en ? "Phone" : "手机";
            case "hud": return "HUD";
            default: return unknown;
        }
    }

    private static String brand(String raw, boolean en, String unknown) {
        switch (sanitizeToken(raw).toLowerCase(Locale.ROOT)) {
            case "vivo": return "vivo";
            case "华为":
            case "huawei": return en ? "Huawei" : "华为";
            case "小米":
            case "xiaomi": return en ? "Xiaomi" : "小米";
            case "oppo": return "OPPO";
            case "漫步者":
            case "edifier": return en ? "Edifier" : "漫步者";
            case "弱水时砂":
            case "rose": return en ? "Rose" : "弱水时砂";
            case "原道":
            case "nicehck": return en ? "NiceHCK" : "原道";
            case "sony": return "Sony";
            case "technics": return "Technics";
            case "system bluetooth": return en ? "System Bluetooth" : "系统蓝牙";
            default: return unknown;
        }
    }

    private static String verification(int level, boolean en, String unknown) {
        switch (level) {
            case Controls.VERIF_REAL: return en ? "Device-verified" : "已实机验证";
            case Controls.VERIF_UNTESTED: return en ? "Experimental" : "实验性支持";
            default: return unknown;
        }
    }

    private static String stage(String raw, boolean en, String unknown) {
        switch (sanitizeToken(raw).toLowerCase(Locale.ROOT)) {
            case "idle": return en ? "Idle" : "未连接";
            case "selecting": return en ? "Selecting device" : "正在选择设备";
            case "connecting": return en ? "Connecting" : "连接中";
            case "live": return en ? "Connected" : "已连接";
            case "parked": return en ? "On demand" : "按需待命";
            default: return unknown;
        }
    }

    private static String role(MeshRole role, boolean en, String unknown) {
        if (role == null) return unknown;
        switch (role) {
            case OFF: return en ? "Off" : "关闭";
            case HOST: return en ? "Host" : "主机共享";
            case CLIENT: return en ? "Client" : "从机遥控";
            default: return unknown;
        }
    }
}

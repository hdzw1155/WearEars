package com.hdz.wearcontrol;

import static com.hdz.wearcontrol.DiagnosticReportBuilder.MeshRole.HOST;

/** Exercises report formatting and the rejection of arbitrary diagnostic text. */
public final class DiagnosticReportChecks {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static void bilingualReport() {
        String zh = DiagnosticReportBuilder.build(new DiagnosticReportBuilder.Input(
                "0.5.0-exp-C", 34, "watch", true, "华为", -1, "parked", HOST,
                false, true, 13, 2, 4, false));
        check(zh.startsWith("WearEars 诊断报告\n已脱敏：不含 MAC、IP、PIN 与设备昵称\n"), "Chinese header");
        check(zh.contains("应用版本 = 0.5.0-exp-C\n") && zh.contains("Android API = 34\n"), "version and API");
        check(zh.contains("终端形态 = 手表\n") && zh.contains("蓝牙权限 = 已授予\n"), "form and permission");
        check(zh.contains("驱动 = 华为\n") && zh.contains("验证等级 = 未知\n"), "Chinese driver and evidence");
        check(zh.contains("连接阶段 = 按需待命\n") && zh.contains("局域网协同角色 = 主机共享\n"), "stage and role");
        check(zh.contains("防火墙 = 关闭\n") && zh.contains("只读模式 = 开启\n"), "firewall switches are independent");
        check(zh.contains("拦截次数 = 13\n") && zh.contains("封禁 IP 数量 = 2\n")
                && zh.contains("支持功能数量 = 4\n"), "aggregate counters");

        String en = DiagnosticReportBuilder.build(new DiagnosticReportBuilder.Input(
                "1.2.3", 30, "HUD", false, "漫步者", Controls.VERIF_REAL, "live",
                DiagnosticReportBuilder.MeshRole.OFF, true, false, 0, 0, 0, true));
        check(en.startsWith("WearEars diagnostic report\nRedacted: no MAC addresses, IP addresses, PINs or device nicknames."),
                "English header");
        check(en.contains("App version = 1.2.3\n") && en.contains("Host form = HUD\n"), "English version and HUD");
        check(en.contains("Bluetooth permission = Denied\n") && en.contains("Driver = Edifier\n"), "English driver and permission");
        check(en.contains("Verification level = Device-verified\n") && en.contains("Connection stage = Connected\n"),
                "English evidence and stage");
    }

    private static void rejectedInputs() {
        String[] secrets = {"De:AD:be:EF:00:F1", "de-ad-be-ef-00-f1", "192.168.47.23", "8.8.4.4", "fd12:3456::7",
                "0429", "PIN=0429", "小雨的耳机", "Office Headset Nickname", "Sony Office Headset Nickname",
                "java.lang.IllegalStateException: internal detail\n\tat user.Device.open(Device.java:97)",
                "LIVE\nInjected", "watch\u0000hidden", "phone\u202Eexternal", "0.5.0-OfficeNickname",
                "OfficeNickname" + "z".repeat(4096)};
        for (boolean english : new boolean[] {false, true}) {
            String unknown = english ? "Unknown" : "未知";
            for (int i = 0; i < secrets.length; i++) {
                String secret = secrets[i];
                String report = DiagnosticReportBuilder.build(new DiagnosticReportBuilder.Input(
                        secret, 34, secret, true, secret, -1, secret, null, true, true, 5, 1, 2, english));
                check(!report.contains(secret), "private input was echoed at index " + i);
                check(report.contains((english ? "App version" : "应用版本") + " = " + unknown + "\n"),
                        "invalid version was accepted");
                check(report.contains((english ? "Host form" : "终端形态") + " = " + unknown + "\n"),
                        "invalid form was accepted");
                check(report.contains((english ? "Driver" : "驱动") + " = " + unknown + "\n"),
                        "invalid driver was accepted");
                check(report.contains((english ? "Connection stage" : "连接阶段") + " = " + unknown + "\n"),
                        "invalid stage was accepted");
                for (String fragment : new String[] {"0429", "internal detail", "at user.Device", "Injected",
                        "OfficeNickname", "\u0000", "\u202E"}) {
                    check(!report.contains(fragment), "private fragment was echoed at index " + i);
                }
            }
        }
    }

    private static void sanitizesTokensAndUnknowns() {
        String scrubbed = DiagnosticReportBuilder.sanitizeToken("aB:01:cd:02:ef:03 10.1.2.3 8.8.4.4 0429");
        check("[redacted] [redacted] [redacted] [redacted]".equals(scrubbed), "MAC, IPs and PIN scrubbed");
        check("vivo".equals(DiagnosticReportBuilder.sanitizeToken("\tvi\u0000vo\u202E\n")), "controls removed");
        check(DiagnosticReportBuilder.sanitizeToken("z".repeat(65)).isEmpty(), "overlong token rejected");
        check(DiagnosticReportBuilder.sanitizeToken(null).isEmpty(), "null token");
        check(DiagnosticReportBuilder.build(null).contains("应用版本 = 未知\n"), "null report input");
        String unknown = DiagnosticReportBuilder.build(new DiagnosticReportBuilder.Input(
                null, -1, "", false, null, 99, null, null, false, false, -1, -1, -1, true));
        check(unknown.contains("Mesh role = Unknown\n") && unknown.contains("Supported features = Unknown\n")
                && unknown.contains("Verification level = Unknown\n"), "unknown fields");
    }

    public static void main(String[] args) {
        bilingualReport();
        rejectedInputs();
        sanitizesTokensAndUnknowns();
        System.out.println("PASS " + checks + " diagnostic report checks");
    }
}

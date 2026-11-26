package com.hdz.wearcontrol.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class WindowsStartupManager {
    private static final String RUN = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String NAME = "WearEarsDesktop";
    private final String launchCommand;

    WindowsStartupManager() {
        launchCommand = currentLaunchCommand();
    }

    boolean available() { return launchCommand != null; }

    boolean registered() throws IOException, InterruptedException {
        Result result = execute(List.of("reg.exe", "query", RUN, "/v", NAME));
        if (result.exitCode == 1) return false;
        result.requireSuccess();
        return result.output.lines().anyMatch(line -> {
            String[] columns = line.strip().split("\\s+", 3);
            return columns.length == 3 && NAME.equalsIgnoreCase(columns[0]) && "REG_SZ".equals(columns[1]);
        });
    }

    boolean setRegistered(boolean enabled) throws IOException, InterruptedException {
        if (!available()) throw new IOException("WearEars launch path is unavailable");
        if (enabled || registered()) execute(registryCommand(launchCommand, enabled)).requireSuccess();
        boolean actual = registered();
        if (actual != enabled) throw new IOException("Run value did not reflect the requested setting");
        return actual;
    }

    static String launchCommand(Path executable, Path jar) {
        if (executable == null || !executable.isAbsolute() || !Files.isRegularFile(executable)) return null;
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".exe")) return null;
        if (!name.equals("java.exe") && !name.equals("javaw.exe")) return quote(executable);
        if (jar == null || !jar.isAbsolute() || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                || !Files.isRegularFile(jar)) return null;
        Path javaw = executable.resolveSibling("javaw.exe");
        return Files.isRegularFile(javaw) ? quote(javaw) + " -jar " + quote(jar) : null;
    }

    static List<String> registryCommand(String command, boolean enabled) {
        if (!enabled) return List.of("reg.exe", "delete", RUN, "/v", NAME, "/f");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Missing WearEars command");
        // ProcessBuilder keeps this pre-quoted native argument intact. The inner quotes belong in REG_SZ.
        String data = "\"" + command.replace("\"", "\\\"") + "\"";
        return List.of("reg.exe", "add", RUN, "/v", NAME, "/t", "REG_SZ", "/d", data, "/f");
    }

    private static String currentLaunchCommand() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            Path executable = info.command().map(Paths::get).orElse(null);
            if (executable == null) return null;
            String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.equals("java.exe") && !name.equals("javaw.exe")) return launchCommand(executable, null);
            Path jar = Paths.get(WindowsStartupManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String[] args = info.arguments().orElse(new String[0]);
            for (int i = 0; i + 1 < args.length; i++) {
                if ("-jar".equals(args[i]) && Files.isSameFile(Paths.get(args[i + 1]), jar)) {
                    return launchCommand(executable, jar);
                }
            }
            String javaCommand = System.getProperty("sun.java.command", "");
            String lower = javaCommand.toLowerCase(Locale.ROOT);
            for (int i = lower.indexOf(".jar"); i >= 0; i = lower.indexOf(".jar", i + 4)) {
                int end = i + 4;
                if ((end == javaCommand.length() || Character.isWhitespace(javaCommand.charAt(end)))
                        && Paths.get(javaCommand.substring(0, end)).toAbsolutePath().normalize().equals(jar)) {
                    return launchCommand(executable, jar);
                }
            }
        } catch (Exception ex) {
            System.err.println("[WearEars] Startup launch path: " + ex);
        }
        return null;
    }

    private static String quote(Path path) {
        return "\"" + path.normalize() + "\"";
    }

    private static Result execute(List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(arguments);
        command.set(0, Paths.get(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "reg.exe").toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) throw new IOException("reg.exe timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            return new Result(process.exitValue(), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
        }
    }

    private static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
        void requireSuccess() throws IOException {
            if (exitCode != 0) throw new IOException("reg.exe exit " + exitCode + ": " + output.strip());
        }
    }
}

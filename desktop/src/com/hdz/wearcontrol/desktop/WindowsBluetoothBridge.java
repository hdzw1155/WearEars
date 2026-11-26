package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.DesktopCoreBridge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Windows native Bluetooth RFCOMM bridge wrapper.
 * Drives WearEarsBtBridge.exe for device enumeration and raw RFCOMM byte streams,
 * implementing DesktopCoreBridge.Sockets (backing core/'s RfcommLink.Sockets).
 */
public final class WindowsBluetoothBridge {
    private static final long LIST_TIMEOUT_MS = 8_000L;

    public static final class BtDevice {
        public final String mac;
        public final String name;
        public final boolean connected;
        public final boolean remembered;

        public BtDevice(String mac, String name, boolean connected, boolean remembered) {
            this.mac = mac == null ? "" : mac.trim().toUpperCase(Locale.ROOT);
            this.name = name == null || name.trim().isEmpty() ? this.mac : name.trim();
            this.connected = connected;
            this.remembered = remembered;
        }

        @Override
        public String toString() {
            return (connected ? "[已连接] " : "[已配对] ") + name + " (" + mac + ")";
        }
    }

    private final Path bridgeExe;

    public WindowsBluetoothBridge() {
        this(resolveDefaultBridgePath());
    }

    public WindowsBluetoothBridge(Path bridgeExe) {
        this.bridgeExe = bridgeExe;
    }

    public Path bridgeExecutable() {
        return bridgeExe;
    }

    public boolean isBridgeAvailable() {
        return bridgeExe != null && Files.isRegularFile(bridgeExe);
    }

    /** Locates WearEarsBtBridge.exe relative to JAR, system property, or workspace build/desktop/. */
    public static Path resolveDefaultBridgePath() {
        String prop = System.getProperty("wearears.bt.bridge");
        if (prop != null && !prop.trim().isEmpty()) {
            Path p = Paths.get(prop.trim());
            if (Files.isRegularFile(p)) return p;
        }
        List<Path> candidates = new ArrayList<>();
        try {
            Path codeLoc = Paths.get(WindowsBluetoothBridge.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(codeLoc) ? codeLoc : codeLoc.getParent();
            if (dir != null) {
                candidates.add(dir.resolve("WearEarsBtBridge.exe"));
                if (dir.getParent() != null) {
                    candidates.add(dir.getParent().resolve("WearEarsBtBridge.exe"));
                    candidates.add(dir.getParent().resolve("build").resolve("desktop").resolve("WearEarsBtBridge.exe"));
                }
            }
        } catch (URISyntaxException | SecurityException | NullPointerException ignored) {
        }
        Path cwd = Paths.get("").toAbsolutePath();
        candidates.add(cwd.resolve("build").resolve("desktop").resolve("WearEarsBtBridge.exe"));
        candidates.add(cwd.resolve("WearEarsBtBridge.exe"));
        if (cwd.getParent() != null) {
            candidates.add(cwd.getParent().resolve("build").resolve("desktop").resolve("WearEarsBtBridge.exe"));
        }
        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c)) {
                return c.toAbsolutePath().normalize();
            }
        }
        Path embedded = extractEmbeddedBridge();
        if (embedded != null) {
            return embedded;
        }
        return cwd.resolve("build").resolve("desktop").resolve("WearEarsBtBridge.exe").toAbsolutePath().normalize();
    }

    private static Path extractEmbeddedBridge() {
        try (InputStream in = WindowsBluetoothBridge.class.getResourceAsStream("/native/WearEarsBtBridge.exe")) {
            if (in == null) return null;
            byte[] bytes = readAllBytes(in);
            if (bytes.length == 0) return null;
            Path stageDir = Paths.get(System.getProperty("java.io.tmpdir", "C:\\Windows\\Temp"), "WearEars-BtBridge");
            Files.createDirectories(stageDir);
            Path out = stageDir.resolve("WearEarsBtBridge-res-" + bytes.length + ".exe");
            if (!Files.isRegularFile(out) || Files.size(out) != bytes.length) {
                Files.write(out, bytes);
            }
            return out.toAbsolutePath().normalize();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    /** Executes WearEarsBtBridge.exe list and parses paired/connected Windows Bluetooth devices. */
    public List<BtDevice> listDevices() throws IOException {
        Path runnableExe = ensureRunnableBridgeExe(bridgeExe);
        ProcessBuilder pb = new ProcessBuilder(runnableExe.toString(), "list");
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outReader = copyAsync(proc.getInputStream(), stdout);
        Thread errReader = copyAsync(proc.getErrorStream(), stderr);
        boolean finished;
        try {
            finished = proc.waitFor(LIST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("枚举蓝牙设备被中断", ie);
        }
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("枚举蓝牙设备超时");
        }
        joinQuietly(outReader);
        joinQuietly(errReader);
        int code = proc.exitValue();
        if (code != 0) {
            String errMsg = new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim();
            throw new IOException("蓝牙枚举失败 (exit=" + code + ")" + (errMsg.isEmpty() ? "" : ": " + errMsg));
        }
        String raw = new String(stdout.toByteArray(), StandardCharsets.UTF_8);
        return parseDeviceList(raw);
    }

    /** Pure deterministic parser for MAC|NAME|CONNECTED|REMEMBERED UTF-8 lines. */
    public static List<BtDevice> parseDeviceList(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return Collections.emptyList();
        }
        List<BtDevice> list = new ArrayList<>();
        String[] lines = rawOutput.split("\\r?\\n");
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\|", -1);
            if (parts.length < 4) continue;
            String mac = parts[0].trim().toUpperCase(Locale.ROOT);
            if (!isValidMac(mac)) continue;
            String name = parts[1].trim();
            boolean connected = "1".equals(parts[2].trim());
            boolean remembered = "1".equals(parts[3].trim());
            list.add(new BtDevice(mac, name, connected, remembered));
        }
        // Connected devices first, then alphabetical by name.
        list.sort((a, b) -> {
            if (a.connected != b.connected) return a.connected ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return list;
    }

    public static boolean isValidMac(String mac) {
        if (mac == null || mac.length() != 17) return false;
        for (int i = 0; i < 17; i++) {
            char c = mac.charAt(i);
            if (i % 3 == 2) {
                if (c != ':') return false;
            } else {
                boolean hex = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
                if (!hex) return false;
            }
        }
        return true;
    }

    /**
     * Creates a DesktopCoreBridge.Sockets factory (backing core/ RfcommLink.Sockets) for a target MAC.
     * Note: Windows Win32 RFCOMM has no separate "insecure" flag; RfcommLink tries insecure=false first,
     * so we skip duplicate insecure=true SDP retries to keep connection snappy.
     */
    public DesktopCoreBridge.Sockets createSockets(String mac) {
        final String targetMac = mac == null ? "" : mac.trim().toUpperCase(Locale.ROOT);
        return (UUID service, boolean insecure, int channel) -> {
            if (service != null && insecure) {
                throw new IOException("Windows RFCOMM already attempted SDP for " + service);
            }
            String target;
            if (service != null) {
                target = service.toString();
            } else if (channel >= 1 && channel <= 30) {
                target = Integer.toString(channel);
            } else {
                throw new IOException("Invalid RFCOMM target: service=null channel=" + channel);
            }
            return new ProcessRfcommSocket(bridgeExe, targetMac, target);
        };
    }

    private static final class ProcessRfcommSocket implements DesktopCoreBridge.Socket {
        private final Path exe;
        private final String mac;
        private final String target;
        private volatile Process process;
        private volatile boolean closed;

        ProcessRfcommSocket(Path exe, String mac, String target) {
            this.exe = exe;
            this.mac = mac;
            this.target = target;
        }

        @Override
        public void connect() throws IOException {
            if (closed) throw new IOException("Socket closed before connect");
            Path runnableExe = ensureRunnableBridgeExe(exe);
            ProcessBuilder pb = new ProcessBuilder(runnableExe.toString(), "connect", mac, target);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            synchronized (this) {
                if (closed) {
                    p.destroyForcibly();
                    throw new IOException("Socket closed while starting bridge");
                }
                this.process = p;
            }

            BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder diag = new StringBuilder();
            String line;
            boolean ok = false;
            while ((line = errReader.readLine()) != null) {
                String trimmed = line.trim();
                if ("CONNECTED".equals(trimmed)) {
                    ok = true;
                    break;
                }
                if (diag.length() > 0) diag.append("; ");
                diag.append(trimmed);
            }
            if (!ok) {
                close();
                String msg = diag.length() > 0 ? diag.toString() : "RFCOMM bridge exited without CONNECTED";
                throw new IOException(msg);
            }

            // Drain any remaining stderr lines asynchronously so the bridge never blocks on stderr pipe.
            Thread drain = new Thread(() -> {
                try {
                    while (errReader.readLine() != null) {
                        // ignore post-connect diagnostics
                    }
                } catch (IOException ignored) {
                }
            }, "WearEars-BtBridgeStderr");
            drain.setDaemon(true);
            drain.start();
        }

        @Override
        public InputStream input() throws IOException {
            Process p = process;
            if (p == null) throw new IOException("Not connected");
            return p.getInputStream();
        }

        @Override
        public OutputStream output() throws IOException {
            Process p = process;
            if (p == null) throw new IOException("Not connected");
            return p.getOutputStream();
        }

        @Override
        public void close() {
            Process p;
            synchronized (this) {
                closed = true;
                p = process;
                process = null;
            }
            if (p != null) {
                try { p.getOutputStream().close(); } catch (Exception ignored) { }
                try { p.getInputStream().close(); } catch (Exception ignored) { }
                try { p.getErrorStream().close(); } catch (Exception ignored) { }
                p.destroy();
                try {
                    if (!p.waitFor(250, TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                }
            }
        }
    }

    /**
     * .NET Framework 4.x CLR startup can fail with 0x8007045A when launched directly from a path
     * containing CJK/non-ASCII characters. If bridgeExe's path contains non-ASCII chars, mirror it
     * to an ASCII-safe local cache path before spawning.
     */
    public static Path ensureRunnableBridgeExe(Path exe) throws IOException {
        if (exe == null || !Files.isRegularFile(exe)) {
            throw new IOException("未找到原生蓝牙桥接程序: " + exe);
        }
        Path normalized = exe.toAbsolutePath().normalize();
        if (isPureAscii(normalized.toString())) {
            return normalized;
        }
        Path stageDir = resolveAsciiStageDir();
        Files.createDirectories(stageDir);
        long size = Files.size(normalized);
        long mtime = Files.getLastModifiedTime(normalized).toMillis();
        Path staged = stageDir.resolve("WearEarsBtBridge-" + size + "-" + mtime + ".exe");
        if (!Files.isRegularFile(staged) || Files.size(staged) != size) {
            Path tmp = stageDir.resolve("WearEarsBtBridge-" + System.nanoTime() + ".tmp");
            Files.copy(normalized, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveRace) {
                Files.deleteIfExists(tmp);
                if (!Files.isRegularFile(staged)) throw moveRace;
            }
        }
        return staged;
    }

    private static Path resolveAsciiStageDir() {
        String sysRoot = System.getenv("SystemRoot");
        String[] candidates = {
                System.getProperty("java.io.tmpdir"),
                System.getenv("LOCALAPPDATA"),
                System.getenv("TEMP"),
                sysRoot != null && !sysRoot.trim().isEmpty() ? Paths.get(sysRoot.trim(), "Temp").toString() : null
        };
        for (String base : candidates) {
            if (base != null && !base.trim().isEmpty() && isPureAscii(base)) {
                return Paths.get(base.trim(), "WearEars-BtBridge");
            }
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "."), "WearEars-BtBridge");
    }

    private static boolean isPureAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    private static Thread copyAsync(InputStream in, OutputStream out) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1024];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) out.write(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        }, "WearEars-BtListIO");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

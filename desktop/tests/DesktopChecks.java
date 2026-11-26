package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.DesktopCoreBridge;
import com.hdz.wearcontrol.DesktopCoreBridge.BrandModuleEntry;
import com.hdz.wearcontrol.FeatureDescriptor;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.LocalMeshFirewall;
import com.hdz.wearcontrol.LocalMeshProtocol;
import com.hdz.wearcontrol.WireDriver;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure JVM verification suite for WearEars Desktop for Windows.
 * Run via: py -X utf8 desktop/build_desktop.py verify
 */
public final class DesktopChecks {
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--argv-probe".equals(args[0])) {
            System.out.println(args[1]);
            return;
        }
        if (args.length == 2 && "--wake-probe".equals(args[0])) {
            try (DesktopInstanceGuard guard = DesktopInstanceGuard.acquire(Path.of(args[1]), () -> { })) {
                if (guard != null) throw new AssertionError("Child acquired the resident lock");
            }
            System.out.println("WAKE acknowledged");
            return;
        }
        checkDriverRegistryAll9Brands();
        checkBtBridgeDeviceListParsing();
        checkLiveWindowsBluetoothBridge();
        checkDesktopSessionMockRfcomm();
        checkMeshAndFirewallLoopback();
        checkAutoConnectCandidates();
        checkPreferences();
        checkInstanceGuard();
        checkStartupCommands();
        checkThemesAndTrayTelemetry();
        checkDpiPaintAndWrapping();
        checkAnimationsAndFeatureDeck();
        checkInitialRefresh();
        checkFindBudsSafety();
        System.out.println("PASS " + checks + " desktop checks");
    }

    private static void assertTrue(boolean cond, String msg) {
        checks++;
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED: " + msg + " expected=" + expected + " actual=" + actual);
        }
    }

    /** 1. Verify all 9 brand DriverModules are assembled, match real device names, and instantiate cleanly. */
    private static void checkDriverRegistryAll9Brands() {
        DesktopDriverRegistry reg = new DesktopDriverRegistry();
        List<BrandModuleEntry> all = reg.allBrands();
        assertEquals(9, all.size(), "Registry must contain all 9 brands");

        String[][] samples = {
                {"vivo", "vivo TWS Air3 Pro"},
                {"huawei", "HUAWEI FreeBuds Pro 3"},
                {"xiaomi", "Redmi Buds 5 Pro"},
                {"oppo", "OPPO Enco X3"},
                {"edifier", "EDIFIER NeoBuds Pro 2"},
                {"rose", "Ceramics MK2"},
                {"nicehck", "OriG in"},
                {"sony", "WF-1000XM5"},
                {"technics", "EAH-AZ80"}
        };

        for (String[] pair : samples) {
            String expectedKey = pair[0];
            String devName = pair[1];
            BrandModuleEntry matched = reg.matchByName(devName);
            assertTrue(matched != null, "Expected brand match for " + devName);
            assertEquals(expectedKey, matched.key, "Matched brand key for " + devName);

            WireDriver driver = reg.createDriver(devName, DesktopDriverRegistry.BRAND_AUTO);
            assertTrue(driver != null, "Driver instantiation for " + devName);
            assertTrue(driver.brandName() != null && !driver.brandName().isEmpty(), "Non-empty brandName for " + expectedKey);
        }

        // Manual brand override fallback for unknown/renamed Bluetooth device
        BrandModuleEntry manual = reg.resolve("My Custom Headphones", "sony");
        assertTrue(manual != null && "sony".equals(manual.key), "Manual brand override must work for unknown names");
        WireDriver manualDriver = reg.createDriver("My Custom Headphones", "vivo");
        assertTrue(manualDriver != null, "Manual driver creation for vivo");
        assertTrue(reg.matchByName("Unknown Generic Speaker") == null, "Unknown device must return null in auto mode");

        // Headphone ranking: compatible TWS headset must beat connected phone/mouse/PC
        WindowsBluetoothBridge.BtDevice iqooPhone = new WindowsBluetoothBridge.BtDevice("11:11:11:11:11:11", "iQOO 12", true, true);
        WindowsBluetoothBridge.BtDevice mouse = new WindowsBluetoothBridge.BtDevice("22:22:22:22:22:22", "Logitech Mouse", true, true);
        WindowsBluetoothBridge.BtDevice air3 = new WindowsBluetoothBridge.BtDevice("44:55:66:77:88:99", "vivo TWS Air3 Pro", false, true);
        List<WindowsBluetoothBridge.BtDevice> ranked = reg.rankDevices(java.util.Arrays.asList(iqooPhone, mouse, air3), "");
        assertEquals("vivo TWS Air3 Pro", ranked.get(0).name, "Matched TWS headset must auto-select ahead of phone");
        assertTrue(reg.scoreDevice(air3, "") > reg.scoreDevice(iqooPhone, ""), "Headset score must exceed phone score");
        assertTrue(reg.scoreDevice(new WindowsBluetoothBridge.BtDevice("33:33:33:33:33:33", "Generic TWS", false, true), "")
                > reg.scoreDevice(iqooPhone, ""), "Generic TWS score must exceed connected phone score");
    }

    /** 2. Verify WearEarsBtBridge list output parsing (UTF-8, flags, sorting, malformed resilience). */
    private static void checkBtBridgeDeviceListParsing() {
        String sample = ""
                + "11:22:33:44:55:66|HUAWEI FreeBuds Pro 3|0|1\n"
                + "aa:bb:cc:dd:ee:ff|vivo TWS Air3 Pro|1|1\r\n"
                + "BAD_LINE_WITHOUT_PIPES\n"
                + "ZZ:ZZ:ZZ:ZZ:ZZ:ZZ|Invalid Mac|1|1\n"
                + "00:11:22:33:44:55|原道 OriG in|1|0\n"
                + "\n";
        List<WindowsBluetoothBridge.BtDevice> devices = WindowsBluetoothBridge.parseDeviceList(sample);
        assertEquals(3, devices.size(), "Parsed valid device count");

        // Connected devices sorted first, then alphabetical
        assertEquals("AA:BB:CC:DD:EE:FF", devices.get(0).mac, "First device MAC normalized uppercase");
        assertEquals("vivo TWS Air3 Pro", devices.get(0).name, "First device name");
        assertTrue(devices.get(0).connected && devices.get(0).remembered, "First device flags");

        assertEquals("00:11:22:33:44:55", devices.get(1).mac, "Second connected device");
        assertEquals("原道 OriG in", devices.get(1).name, "UTF-8 CJK device name preserved");
        assertTrue(devices.get(1).connected && !devices.get(1).remembered, "Second device flags");

        assertEquals("11:22:33:44:55:66", devices.get(2).mac, "Disconnected device sorted after connected");
        assertTrue(!devices.get(2).connected && devices.get(2).remembered, "Third device flags");

        assertTrue(WindowsBluetoothBridge.isValidMac("AA:BB:CC:DD:EE:FF"), "Valid MAC");
        assertTrue(!WindowsBluetoothBridge.isValidMac("AA-BB-CC-DD-EE-FF"), "Dash MAC rejected");
        assertEquals(0, WindowsBluetoothBridge.parseDeviceList("").size(), "Empty list returns 0");
    }

    /** 2b. Live smoke test of Java WindowsBluetoothBridge -> WearEarsBtBridge.exe list (including CJK-path staging). */
    private static void checkLiveWindowsBluetoothBridge() throws Exception {
        WindowsBluetoothBridge bridge = new WindowsBluetoothBridge();
        assertTrue(bridge.isBridgeAvailable(), "WearEarsBtBridge.exe must be resolved and present");
        List<WindowsBluetoothBridge.BtDevice> live = bridge.listDevices();
        assertTrue(live != null, "Live listDevices() must return non-null list");
        System.out.println("Windows Bluetooth bridge list: " + live.size() + " device(s)");
        for (WindowsBluetoothBridge.BtDevice d : live) {
            assertTrue(WindowsBluetoothBridge.isValidMac(d.mac), "Live device MAC valid: " + d.mac);
            assertTrue(d.name != null && !d.name.isEmpty(), "Live device name non-empty");
        }
    }

    /** 3. Verify DesktopSession + core RfcommLink + real NiceHCK WireDriver over piped mock socket. */
    private static void checkDesktopSessionMockRfcomm() throws Exception {
        DesktopDriverRegistry reg = new DesktopDriverRegistry();
        DesktopSession session = new DesktopSession(reg, new WindowsBluetoothBridge());

        PipedOutputStream deviceTx = new PipedOutputStream();
        PipedInputStream hostRx = new PipedInputStream(deviceTx);
        PipedOutputStream hostTx = new PipedOutputStream();
        PipedInputStream deviceRx = new PipedInputStream(hostTx);

        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch battLatch = new CountDownLatch(1);
        AtomicReference<DesktopCoreBridge.StateSnapshotView> lastView = new AtomicReference<>();

        session.addListener(new DesktopSession.SessionListener() {
            @Override
            public void onPhaseChanged(DesktopSession.ConnectionPhase phase, String detail) {
                if (phase == DesktopSession.ConnectionPhase.CONNECTED) {
                    connectedLatch.countDown();
                }
            }

            @Override
            public void onStateChanged(DesktopCoreBridge.StateSnapshotView state) {
                lastView.set(state);
                if (state.left == 85 && state.right == 90 && state.box == 70) {
                    battLatch.countDown();
                }
            }
        });

        DesktopCoreBridge.Sockets mockSockets = (UUID service, boolean insecure, int channel) -> new DesktopCoreBridge.Socket() {
            @Override public void connect() { }
            @Override public InputStream input() { return hostRx; }
            @Override public OutputStream output() { return hostTx; }
            @Override public void close() throws IOException {
                try { hostRx.close(); } catch (IOException ignored) { }
                try { hostTx.close(); } catch (IOException ignored) { }
            }
        };

        // Drain host outbound commands on background thread so piped buffer never stalls
        Thread drainer = new Thread(() -> {
            byte[] buf = new byte[256];
            try {
                while (deviceRx.read(buf) >= 0) {
                    // consume
                }
            } catch (IOException ignored) {
            }
        });
        drainer.setDaemon(true);
        drainer.start();

        session.connectWithSockets("00:11:22:33:44:55", "OriG in", "nicehck", mockSockets);
        assertTrue(connectedLatch.await(3, TimeUnit.SECONDS), "NiceHCK session must reach CONNECTED via RfcommLink");

        // Feed a real NiceHCK battery frame: 4E | len_lo(6) len_hi(0) | 00 | op_lo(05) op_hi(00) | L(85) R(90) B(70)
        byte[] nicehckBattFrame = new byte[]{0x4E, 0x06, 0x00, 0x00, 0x05, 0x00, (byte) 85, (byte) 90, (byte) 70};
        deviceTx.write(nicehckBattFrame);
        deviceTx.flush();

        assertTrue(battLatch.await(3, TimeUnit.SECONDS), "DesktopSession must decode NiceHCK battery frame");
        DesktopCoreBridge.StateSnapshotView v = lastView.get();
        assertTrue(v != null && v.connected, "Session view connected");
        assertEquals(85, v.left, "Left battery decoded");
        assertEquals(90, v.right, "Right battery decoded");
        assertEquals(70, v.box, "Box battery decoded");

        session.disconnect();
        session.shutdown();
        deviceTx.close();
        deviceRx.close();
    }

    /** 4. Verify DesktopMeshNode + LocalMeshFirewall over real 127.0.0.1 TCP loopback. */
    private static void checkMeshAndFirewallLoopback() throws Exception {
        DesktopMeshNode host = new DesktopMeshNode();
        DesktopMeshNode client = new DesktopMeshNode();
        host.setPin("4829");
        host.setHostName("WinTestHost");
        client.setHostName("WinTestClient");

        CountDownLatch hostStarted = new CountDownLatch(1);
        CountDownLatch noiseReceived = new CountDownLatch(1);
        CountDownLatch refreshReceived = new CountDownLatch(1);
        CountDownLatch featureReceived = new CountDownLatch(1);
        AtomicInteger gotNoise = new AtomicInteger(-1);
        AtomicInteger gotEq = new AtomicInteger(-1);
        AtomicInteger findBudsHits = new AtomicInteger(0);
        AtomicInteger portRef = new AtomicInteger(0);

        host.pushSnapshot(new LocalMeshProtocol.StateSnapshot(
                true, false, Controls.CAP_NOISE | Controls.CAP_BATTERY,
                Controls.MODE_OFF, 88, 92, 65, 0, -1, -1, 1, -1, "vivo TWS Air3 Pro"));

        host.startHost(0, false, new DesktopMeshNode.HostCommandSink() {
            @Override
            public void onRemoteNoiseMode(int mode) {
                gotNoise.set(mode);
                noiseReceived.countDown();
            }

            @Override
            public void onRemoteRefresh() {
                refreshReceived.countDown();
            }

            @Override
            public void onRemoteFeature(FeatureId id, int value) {
                if (id == FeatureId.FIND_MY_BUDS) {
                    findBudsHits.incrementAndGet();
                } else if (id == FeatureId.EQUALIZER) {
                    gotEq.set(value);
                    featureReceived.countDown();
                }
            }
        }, new DesktopMeshNode.HostListener() {
            @Override
            public void onHostStarted(String ip, int port, String pin) {
                portRef.set(port);
                hostStarted.countDown();
            }
        });

        assertTrue(hostStarted.await(3, TimeUnit.SECONDS), "Mesh host must start on ephemeral TCP port");
        int port = portRef.get();
        assertTrue(port > 0, "Ephemeral port bound");

        // 4a. Wrong PIN x3 triggers LocalMeshFirewall lockout ban
        try (Socket raw = new Socket("127.0.0.1", port)) {
            raw.setSoTimeout(3000);
            InputStream in = raw.getInputStream();
            OutputStream out = raw.getOutputStream();
            for (int i = 0; i < LocalMeshFirewall.MAX_AUTH_FAILS; i++) {
                LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeHello("0000", "BruteClient"));
                String resp = LocalMeshProtocol.readLineBounded(in);
                assertEquals("AUTH_ERR", resp, "Wrong PIN must receive AUTH_ERR");
            }
        }
        assertTrue(host.firewall().isBanned("127.0.0.1"), "3 wrong PINs must trigger firewall lockout");
        assertTrue(host.firewall().bannedIpCount() >= 1, "Banned IP count >= 1");

        // Clear ban so legitimate client can connect
        host.firewall().clearBans();
        assertTrue(!host.firewall().isBanned("127.0.0.1"), "clearBans must unban 127.0.0.1");

        // 4b. Legitimate Client auth + State sync + CMD forwarding
        CountDownLatch clientAuthed = new CountDownLatch(1);
        CountDownLatch stateSynced = new CountDownLatch(1);
        AtomicReference<LocalMeshProtocol.StateSnapshot> syncedSnap = new AtomicReference<>();

        client.connectClient("127.0.0.1", port, "4829", new DesktopMeshNode.ClientListener() {
            @Override
            public void onClientConnected(String hostName) {
                if ("WinTestHost".equals(hostName)) clientAuthed.countDown();
            }

            @Override
            public void onClientState(LocalMeshProtocol.StateSnapshot state) {
                syncedSnap.set(state);
                stateSynced.countDown();
            }
        });

        assertTrue(clientAuthed.await(3, TimeUnit.SECONDS), "Client must authenticate with valid PIN");
        assertTrue(stateSynced.await(3, TimeUnit.SECONDS), "Client must receive initial STATE snapshot");
        assertEquals(88, syncedSnap.get().left, "Synced left battery");
        assertEquals(92, syncedSnap.get().right, "Synced right battery");
        assertEquals("vivo TWS Air3 Pro", syncedSnap.get().deviceName, "Synced device name");

        // Forward CMD_NOISE and CMD_FEATURE(EQUALIZER)
        assertTrue(client.sendRemoteNoiseMode(Controls.MODE_ANC), "Send remote ANC");
        assertTrue(noiseReceived.await(3, TimeUnit.SECONDS), "Host must receive remote ANC");
        assertEquals(Controls.MODE_ANC, gotNoise.get(), "Forwarded noise mode == MODE_ANC");

        assertTrue(client.sendRemoteFeature(FeatureId.EQUALIZER, 2), "Send remote EQ");
        assertTrue(featureReceived.await(3, TimeUnit.SECONDS), "Host must receive remote EQ");
        assertEquals(2, gotEq.get(), "Forwarded EQ == 2");

        // Client-side hard block on FIND_MY_BUDS
        assertTrue(!client.sendRemoteFeature(FeatureId.FIND_MY_BUDS, 3), "Client must reject FIND_MY_BUDS");

        // 4c. Read-only mode blocks writes while allowing CMD_REFRESH
        host.firewall().setReadOnlyMode(true);
        int blockedBefore = host.firewall().blockedCount();
        client.sendRemoteNoiseMode(Controls.MODE_TRANS);
        client.sendRemoteRefresh();
        assertTrue(refreshReceived.await(3, TimeUnit.SECONDS), "Read-only mode must still allow CMD_REFRESH");
        Thread.sleep(150);
        assertEquals(Controls.MODE_ANC, gotNoise.get(), "Read-only mode must block CMD_NOISE write");
        assertTrue(host.firewall().blockedCount() > blockedBefore, "Firewall blockedCount incremented for read-only write");

        // 4d. Raw wire FIND_MY_BUDS hard-blocked even if firewall is disabled
        host.firewall().setEnabled(false);
        try (Socket raw = new Socket("127.0.0.1", port)) {
            raw.setSoTimeout(3000);
            InputStream in = raw.getInputStream();
            OutputStream out = raw.getOutputStream();
            LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeHello("4829", "RawTester"));
            String ok = LocalMeshProtocol.readLineBounded(in);
            assertTrue(ok != null && ok.startsWith("AUTH_OK|"), "Raw tester authenticated");
            LocalMeshProtocol.readLineBounded(in); // initial STATE
            LocalMeshProtocol.writeFrame(out, "CMD|FEATURE|FIND_MY_BUDS|3");
            LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodePing());
            String pong = LocalMeshProtocol.readLineBounded(in);
            assertEquals("PONG", pong, "Session alive after rejected raw FIND_MY_BUDS");
        }
        assertEquals(0, findBudsHits.get(), "FIND_MY_BUDS must NEVER reach HostCommandSink");

        client.stopAll();
        host.stopAll();
    }

    private static void checkAutoConnectCandidates() {
        DesktopDriverRegistry registry = new DesktopDriverRegistry();
        WindowsBluetoothBridge.BtDevice phone = new WindowsBluetoothBridge.BtDevice(
                "11:11:11:11:11:11", "iQOO 12", true, true);
        WindowsBluetoothBridge.BtDevice online = new WindowsBluetoothBridge.BtDevice(
                "22:22:22:22:22:22", "vivo TWS Air3 Pro", true, true);
        WindowsBluetoothBridge.BtDevice offline = new WindowsBluetoothBridge.BtDevice(
                "AA:AA:AA:AA:AA:AA", "WF-1000XM5", false, true);
        WindowsBluetoothBridge.BtDevice renamed = new WindowsBluetoothBridge.BtDevice(
                "BB:BB:BB:BB:BB:BB", "Studio pair", true, true);
        List<WindowsBluetoothBridge.BtDevice> ranked = registry.rankDevices(List.of(phone, offline, online), offline.mac);
        assertTrue(ranked.indexOf(online) < ranked.indexOf(phone), "Rank online vivo ahead of iQOO phone");
        assertEquals(online, registry.findAutoConnectCandidate(ranked, offline.mac), "Skip higher ranked offline earphones");
        assertEquals(null, registry.findAutoConnectCandidate(List.of(phone, offline, renamed), ""), "Skip phone and unknown devices");
        assertEquals(renamed, registry.findAutoConnectCandidate(List.of(renamed), " bb:bb:bb:bb:bb:bb "),
                "Allow an online, renamed headset with a remembered MAC");
        assertEquals(null, registry.findAutoConnectCandidate(List.of(offline), offline.mac), "Remembered headset must be online");
        assertEquals(null, registry.findAutoConnectCandidate(List.of(), ""), "No startup target on empty scan");
    }

    private static void checkPreferences() throws IOException {
        Path dir = Files.createTempDirectory("wear-prefs-test-");
        Path file = dir.resolve("desktop.properties");
        try {
            DesktopPrefs empty = new DesktopPrefs(file);
            assertEquals("minimal", empty.uiStyle(), "Default appearance");
            assertEquals("zh", empty.language(), "Default language");
            assertTrue(empty.autoConnect(), "Auto-connect defaults on");
            assertTrue(!empty.startMinimized(), "Start minimized defaults off");
            assertTrue(empty.autoHideOnBlur(), "Blur dismissal defaults on");
            assertTrue(!empty.pinned(), "Pin defaults off");
            LocalMeshFirewall fw = new LocalMeshFirewall();
            empty.applyToFirewall(fw);
            assertTrue(fw.isEnabled() && fw.isSameSubnetOnly(), "Firewall and subnet restriction default on");
            Files.writeString(file, "disclaimer_accepted=true\nlast_brand=vivo\n", StandardCharsets.ISO_8859_1);
            DesktopPrefs legacy = new DesktopPrefs(file);
            assertTrue(legacy.disclaimerAccepted() && legacy.autoConnect(), "Existing prefs retain consent and get new defaults");
            assertEquals("vivo", legacy.lastBrand(), "Existing protocol override");
            legacy.setUiStyle("liquid");
            legacy.setLanguage("en");
            legacy.setAutoConnect(false);
            legacy.setStartMinimized(true);
            legacy.setAutoHideOnBlur(false);
            legacy.setPinned(true);
            legacy.rememberConnection("aa:bb:cc:dd:ee:ff", "sony");
            DesktopPrefs saved = new DesktopPrefs(file);
            assertEquals("liquid", saved.uiStyle(), "Appearance survives reload");
            assertEquals("en", saved.language(), "Language survives reload");
            assertTrue(!saved.autoConnect() && saved.startMinimized(), "Startup switches survive reload");
            assertTrue(!saved.autoHideOnBlur() && saved.pinned(), "Tray behavior survives reload");
            assertEquals("AA:BB:CC:DD:EE:FF", saved.lastMac(), "Remembered MAC normalized");
            assertEquals("sony", saved.lastBrand(), "Remembered brand and MAC saved together");
            saved.setUiStyle("invalid");
            saved.setLanguage("invalid");
            assertEquals("minimal", new DesktopPrefs(file).uiStyle(), "Unknown skin falls back");
            assertEquals("zh", new DesktopPrefs(file).language(), "Unknown language falls back");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static void checkInstanceGuard() throws Exception {
        assertEquals(42678, DesktopInstanceGuard.parsePort(" 42678\r\n"), "Valid wake port");
        for (String invalid : List.of("", "0", "65536", "abc", "-1", "40000\n40001")) {
            assertEquals(-1, DesktopInstanceGuard.parsePort(invalid), "Reject malformed wake port");
        }
        Path dir = Files.createTempDirectory("wear-lock-test-");
        Path file = dir.resolve("desktop.lock");
        Files.writeString(file, "broken\n");
        CountDownLatch wakes = new CountDownLatch(2);
        try {
            try (DesktopInstanceGuard guard = DesktopInstanceGuard.acquire(file, wakes::countDown)) {
                assertTrue(guard != null, "Take over stale file");
                int port = DesktopInstanceGuard.readPort(file);
                assertTrue(port > 0, "Read resident port while lock is held");
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(1500);
                    socket.getOutputStream().write("NOPE\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals(-1, socket.getInputStream().read(), "Malformed wake receives no acknowledgement");
                }
                assertEquals(2L, wakes.getCount(), "Invalid input never wakes the app");
                try (DesktopInstanceGuard second = DesktopInstanceGuard.acquire(file, () -> { })) {
                    assertEquals(null, second, "Second acquisition wakes resident instance");
                }
                Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
                Process child = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                        DesktopChecks.class.getName(), "--wake-probe", file.toString()).redirectErrorStream(true).start();
                try {
                    assertTrue(child.waitFor(8, TimeUnit.SECONDS), "Secondary Windows process completed");
                    String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                    assertEquals(0, child.exitValue(), "Secondary Windows process exit: " + output);
                    assertEquals("WAKE acknowledged", output, "Cross-process wake protocol");
                } finally {
                    if (child.isAlive()) child.destroyForcibly();
                }
                assertTrue(wakes.await(2, TimeUnit.SECONDS), "Resident observed local and cross-process wake callbacks");
            }
            try (DesktopInstanceGuard next = DesktopInstanceGuard.acquire(file, () -> { })) {
                assertTrue(next != null, "Release lock and recover on restart");
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static void checkStartupCommands() throws Exception {
        Path dir = Files.createTempDirectory("WearEars with spaces ");
        Path exe = Files.createFile(dir.resolve("WearEars & Lab.exe"));
        Path java = Files.createFile(dir.resolve("java.exe"));
        Path javaw = Files.createFile(dir.resolve("javaw.exe"));
        Path jar = Files.createFile(dir.resolve("WearEars Desktop.jar"));
        try {
            assertEquals("\"" + exe + "\"", WindowsStartupManager.launchCommand(exe, null), "Packaged executable is fully quoted");
            String expected = "\"" + javaw + "\" -jar \"" + jar + "\"";
            assertEquals(expected, WindowsStartupManager.launchCommand(java, jar), "JAR run uses quoted javaw and JAR paths");
            assertEquals(null, WindowsStartupManager.launchCommand(java, null), "Classpath run cannot be registered");
            assertEquals(null, WindowsStartupManager.launchCommand(dir.resolve("missing.exe"), null), "Missing executable rejected");
            List<String> add = WindowsStartupManager.registryCommand(expected, true);
            assertEquals(List.of("reg.exe", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "/v", "WearEarsDesktop", "/t", "REG_SZ", "/d",
                    "\"\\\"" + javaw + "\\\" -jar \\\"" + jar + "\\\"\"", "/f"), add,
                    "Registry add command preserves literal quotes as part of one data argument");
            assertEquals(List.of("reg.exe", "delete", add.get(2), "/v", "WearEarsDesktop", "/f"),
                    WindowsStartupManager.registryCommand(expected, false), "Delete exactly the WearEars startup value");
            String launcher = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            Process probe = new ProcessBuilder(launcher, "-Dfile.encoding=UTF-8", "-cp",
                    System.getProperty("java.class.path"), DesktopChecks.class.getName(),
                    "--argv-probe", add.get(8)).redirectErrorStream(true).start();
            try {
                assertTrue(probe.waitFor(8, TimeUnit.SECONDS), "Startup quoting argv probe completed");
                String received = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                assertEquals(0, probe.exitValue(), "Native argv probe exit: " + received);
                assertEquals(expected, received, "Windows command-line parser preserves startup quotes");
            } finally {
                if (probe.isAlive()) probe.destroyForcibly();
            }
        } finally {
            Files.delete(exe);
            Files.delete(java);
            Files.delete(javaw);
            Files.delete(jar);
            Files.delete(dir);
        }
    }

    private static void checkThemesAndTrayTelemetry() {
        DesktopTheme[] themes = DesktopTheme.values();
        int[][] colors = {{0xF4F3EE, 0xFFFFFF, 0x111317, 0x111317, 0x4A505C, 0x0D4FB8, 0x0D4FB8, 0xDF1B22},
                {0, 0x18150E, 0x44351D, 0xFFFFFF, 0xD8C79A, 0xF5B841, 0xF5B841, 0xF0524F},
                {0, 0x121824, 0x263652, 0xFFFFFF, 0xB8CEEE, 0x4C8DFF, 0x3A80F5, 0xFA5151},
                {0, 0x111A16, 0x254436, 0xFFFFFF, 0xB8D8CA, 0x5EDDA6, 0x22996A, 0xF0524F}};
        assertEquals(4, themes.length, "Four desktop skins");
        for (int i = 0; i < themes.length; i++) {
            DesktopTheme t = themes[i];
            assertEquals(t, DesktopTheme.forKey(t.key), "Theme key round trip");
            Color[] actual = {t.page, t.card, t.border, t.text, t.muted, t.accent, t.pill, t.danger};
            for (int j = 0; j < actual.length; j++) {
                assertEquals(colors[i][j], actual[j].getRGB() & 0xffffff, "Color " + j + " of " + t.key);
            }
        }
        assertEquals(0xF6C100, DesktopTheme.CHROME_YELLOW.getRGB() & 0xffffff, "Suprematist chrome yellow");
        DesktopStrings zh = new DesktopStrings("zh");
        DesktopStrings en = new DesktopStrings("en");
        DesktopCoreBridge.StateHolder holder = new DesktopCoreBridge.StateHolder();
        assertEquals("WearEars · 未连接（点击打开）",
                zh.tooltip(DesktopSession.ConnectionPhase.DISCONNECTED, holder.view()), "Disconnected tooltip");
        holder.begin("AA:BB:CC:DD:EE:FF", "vivo TWS Air3 Pro", "vivo", Controls.CAP_BATTERY, 0, 0);
        holder.setConnected(true, null, Controls.CAP_BATTERY, 0, null);
        holder.battery(100, 255, 50, 5, true);
        holder.mode(Controls.MODE_ANC);
        assertEquals("WearEars · vivo TWS Air3 Pro\nL 100% ⚡  R --  盒 50% ⚡ · 降噪",
                zh.tooltip(DesktopSession.ConnectionPhase.CONNECTED, holder.view()), "Tooltip with missing earbud and charge mask");
        holder.setConnected(true, "🎧".repeat(100), Controls.CAP_BATTERY_SINGLE, 0, null);
        holder.battery(0, 70, 80, 1, true);
        String tooltip = en.tooltip(DesktopSession.ConnectionPhase.CONNECTED, holder.view());
        assertTrue(tooltip.length() <= 127, "Tray length fits the Windows limit");
        assertTrue(tooltip.endsWith("\nTOTAL 0% ⚡ · ANC"), "Single battery and 0% stay visible with a long name");
        assertTrue(tooltip.matches("(?s)[^\\p{Cs}]*"), "Tooltip preserves surrogate pairs");
        assertEquals(new Rectangle(-422, 330, 410, 680),
                DesktopTrayManager.anchor(new Rectangle(-1600, 40, 1600, 982), 410, 680), "Negative-origin monitor work area");
        assertEquals(new Rectangle(12, 12, 316, 456),
                DesktopTrayManager.anchor(new Rectangle(0, 0, 340, 480), 410, 680), "Compact work area clamps card size");
        assertEquals(64, DesktopTrayManager.image(Color.GREEN).getWidth(), "64 px tray source art");
    }

    private static void checkDpiPaintAndWrapping() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (DesktopTheme theme : DesktopTheme.values()) {
                DesktopUi ui = new DesktopUi(theme, "zh");
                DesktopBatteryPanel battery = new DesktopBatteryPanel(ui);
                int width = 254;
                battery.setState(Controls.CAP_BATTERY, 100, 255, 99, 5);
                battery.setSize(width, battery.getPreferredSize().height);
                assertTrue(battery.getToolTipText().contains("100% ⚡") && battery.getToolTipText().contains("R --"),
                        "Accessible telemetry for " + theme.key);
                for (double scale : new double[] {1, 1.25, 1.5, 1.75, 2}) {
                    BufferedImage canvas = new BufferedImage((int) Math.ceil(width * scale),
                            (int) Math.ceil(battery.getHeight() * scale), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = canvas.createGraphics();
                    graphics.scale(scale, scale);
                    battery.printAll(graphics);
                    java.awt.FontMetrics fitted = DesktopUi.fit(graphics, "100%", DesktopTheme.font(Font.BOLD, 30), 42, 30);
                    assertTrue(fitted.stringWidth("100%") <= 42 && fitted.getAscent() + fitted.getDescent() <= 30,
                            "100% fits at " + scale + " DPI for " + theme.key);
                    graphics.dispose();
                    assertTrue((canvas.getRGB(canvas.getWidth() / 4, canvas.getHeight() / 2) >>> 24) != 0,
                            "Battery painted at " + scale + " DPI for " + theme.key);
                }
                DesktopNoiseSegmentedBar noise = new DesktopNoiseSegmentedBar(ui, mode -> { });
                noise.setState(Controls.MODE_ANC, true);
                noise.setState(Controls.MODE_OFF, false);
                assertTrue(!noise.isEnabled(), "Noise disabled without support for " + theme.key);
                SwissDialPanel dial = new SwissDialPanel();
                dial.applyTheme(ui);
                assertEquals(theme.page, dial.getBackground(), "Spatial dial follows theme " + theme.key);
            }
            DesktopUi ui = new DesktopUi(DesktopTheme.MINIMAL, "en");
            JPanel row = ui.row(ui.button("First equalizer preset", () -> { }), ui.button("Second equalizer preset", () -> { }));
            Dimension wide = row.getPreferredSize();
            row.setSize(180, 80);
            row.doLayout();
            assertTrue(row.getComponent(1).getY() > row.getComponent(0).getY(), "Preset controls wrap on compact screen");
            assertTrue(row.getComponent(1).getX() + row.getComponent(1).getWidth() <= 180, "No horizontal overflow");
            assertTrue(wide.width >= row.getWidth(), "Wide preferred size");
        });
    }

    private static void checkAnimationsAndFeatureDeck() throws Exception {
        DesktopSession session = new DesktopSession(new DesktopDriverRegistry(), new WindowsBluetoothBridge());
        try {
            SwingUtilities.invokeAndWait(() -> {
                DesktopUi ui = new DesktopUi(DesktopTheme.LIQUID, "en");
                DesktopBatteryPanel battery = new DesktopBatteryPanel(ui);
                DesktopNoiseSegmentedBar noise = new DesktopNoiseSegmentedBar(ui, mode -> { });
                JPanel container = new JPanel();
                container.add(battery);
                container.add(noise);
                battery.setState(Controls.CAP_BATTERY, 100, 50, 80, 0);
                noise.setState(Controls.MODE_OFF, true);
                assertTrue(!battery.isAnimating() && !noise.isAnimating(), "Detached controls run no timers");
                container.addNotify();
                try {
                    noise.setState(Controls.MODE_ANC, true);
                    assertTrue(battery.isAnimating() && noise.isAnimating(), "Visible controls animate");
                    container.setVisible(false);
                    assertTrue(!battery.isAnimating() && !noise.isAnimating(), "Hiding the panel stops both timers");
                } finally {
                    container.removeNotify();
                }
                DesktopFeatureDeck deck = new DesktopFeatureDeck(new DesktopPrefs(null), session, null);
                JPanel panel = deck.view(ui);
                DesktopCoreBridge.StateHolder backend = new DesktopCoreBridge.StateHolder();
                int caps = Controls.CAP_NOISE | Controls.CAP_SPATIAL;
                backend.begin("AA:BB:CC:DD:EE:FF", "Desktop fixture", "vivo", caps, 0, 0);
                backend.setConnected(true, null, caps, 0, null);
                backend.featureSpec(FeatureDescriptor.choice(FeatureId.ANC_LEVEL, Controls.VERIF_REAL, false,
                        new com.hdz.wearcontrol.OptionItem(1, "深度", "Deep")));
                backend.featureSpec(FeatureDescriptor.toggle(FeatureId.WIND_REDUCTION, Controls.VERIF_REAL, false));
                backend.featureSpec(FeatureDescriptor.readOnly(FeatureId.MULTIPOINT, Controls.VERIF_REAL, false));
                backend.featureText(FeatureId.MULTIPOINT, "Phone and PC");
                backend.featureValue(FeatureId.WIND_REDUCTION, 0);
                deck.update(backend.view());
                List<String> titles = java.util.Arrays.stream(panel.getComponents())
                        .map(card -> ((JTextArea) ((JPanel) card).getComponent(0)).getText())
                        .collect(java.util.stream.Collectors.toList());
                assertTrue(titles.contains("ANC level") && titles.contains("Wind reduction"), "ANC and wind use separate cards");
                assertTrue(titles.contains("Multipoint"), "Read-only multipoint card remains visible");
                assertTrue(titles.contains("Headphone spatial audio [Experimental]"), "Legacy spatial visible and marked experimental");
                assertEquals("Microsoft YaHei UI", DesktopTheme.font(Font.BOLD, 12).getName(), "Mixed-script UI font");
                deck.close();
            });
        } finally {
            session.shutdown();
            assertTrue(session.awaitShutdown(3, TimeUnit.SECONDS), "Feature fixture releases the actor");
        }
    }

    private static void checkInitialRefresh() throws Exception {
        try (MockTelemetry offline = new MockTelemetry()) {
            assertTrue(offline.queryFallback.await(4, TimeUnit.SECONDS), "Missing initial reports trigger both fallback wire queries");
            assertEquals(2, offline.batteryQueries.get(), "One initial and one fallback battery query");
            assertEquals(2, offline.noiseQueries.get(), "One initial and one fallback noise query");
            Thread.sleep(1300);
            assertEquals(2, offline.batteryQueries.get(), "Startup battery fallback never repeats");
            assertEquals(2, offline.noiseQueries.get(), "Startup noise fallback never repeats");
        }
        try (MockTelemetry partial = new MockTelemetry()) {
            partial.send(0x05, 0, 100, 255, 0);
            assertTrue(partial.batteryReceived.await(2, TimeUnit.SECONDS), "One reported earbud counts as battery telemetry");
            partial.send(0x01, 0x01, 0);
            assertTrue(partial.noiseReceived.await(2, TimeUnit.SECONDS), "Confirmed noise status suppresses fallback");
            assertTrue(!partial.queryFallback.await(1550, TimeUnit.MILLISECONDS), "No fallback on partial battery plus noise");
            assertEquals(-1, partial.session.snapshot().right, "Unknown earbud stays --");
            partial.send(0x05, 0, 255, 97, 70);
            assertTrue(partial.rightReceived.await(2, TimeUnit.SECONDS), "Second full battery report decoded");
            assertEquals(-1, partial.session.snapshot().left, "Full report clears a previously present earbud");
        }
        try (MockTelemetry cancelled = new MockTelemetry()) {
            cancelled.session.disconnect();
            assertTrue(cancelled.disconnected.await(2, TimeUnit.SECONDS), "Disconnect during startup");
            assertTrue(!cancelled.queryFallback.await(1450, TimeUnit.MILLISECONDS), "Disconnect cancels the fallback");
        }
    }

    private static final class MockTelemetry implements AutoCloseable {
        final DesktopSession session = new DesktopSession(new DesktopDriverRegistry(), new WindowsBluetoothBridge());
        final PipedOutputStream deviceTx = new PipedOutputStream();
        final PipedInputStream hostRx = new PipedInputStream(deviceTx);
        final AtomicInteger batteryQueries = new AtomicInteger();
        final AtomicInteger noiseQueries = new AtomicInteger();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final CountDownLatch batteryReceived = new CountDownLatch(1);
        final CountDownLatch noiseReceived = new CountDownLatch(1);
        final CountDownLatch rightReceived = new CountDownLatch(1);
        final CountDownLatch queryFallback = new CountDownLatch(1);

        MockTelemetry() throws Exception {
            session.addListener(new DesktopSession.SessionListener() {
                @Override public void onPhaseChanged(DesktopSession.ConnectionPhase phase, String detail) {
                    if (phase == DesktopSession.ConnectionPhase.CONNECTED) connected.countDown();
                    if (phase == DesktopSession.ConnectionPhase.DISCONNECTED) disconnected.countDown();
                }
                @Override public void onStateChanged(DesktopCoreBridge.StateSnapshotView snap) {
                    if (snap.left == 100) batteryReceived.countDown();
                    if (snap.mode == Controls.MODE_OFF) noiseReceived.countDown();
                    if (snap.right == 97) rightReceived.countDown();
                }
            });
            OutputStream written = new OutputStream() {
                @Override public void write(int value) { }
                @Override public void write(byte[] data, int offset, int len) {
                    if (len < 6 || data[offset] != 0x4e) return;
                    int op = (data[offset + 4] & 0xff) | ((data[offset + 5] & 0xff) << 8);
                    if (op == 0x0005) batteryQueries.incrementAndGet();
                    if (op == 0x0101) noiseQueries.incrementAndGet();
                    if (batteryQueries.get() >= 2 && noiseQueries.get() >= 2) queryFallback.countDown();
                }
            };
            session.connectWithSockets("00:11:22:33:44:55", "OriG in", "nicehck",
                    (service, insecure, channel) -> new DesktopCoreBridge.Socket() {
                        @Override public void connect() { }
                        @Override public InputStream input() { return hostRx; }
                        @Override public OutputStream output() { return written; }
                        @Override public void close() throws IOException { hostRx.close(); }
                    });
            assertTrue(connected.await(3, TimeUnit.SECONDS), "Telemetry mock handshakes");
        }

        void send(int opLow, int opHigh, int... body) throws IOException {
            byte[] frame = new byte[6 + body.length];
            frame[0] = 0x4e;
            frame[1] = (byte) (body.length + 3);
            frame[4] = (byte) opLow;
            frame[5] = (byte) opHigh;
            for (int i = 0; i < body.length; i++) frame[6 + i] = (byte) body[i];
            deviceTx.write(frame);
            deviceTx.flush();
        }

        @Override public void close() throws Exception {
            session.shutdown();
            assertTrue(session.awaitShutdown(3, TimeUnit.SECONDS), "Session releases actor and RFCOMM on shutdown");
            deviceTx.close();
            hostRx.close();
        }
    }

    private static void checkFindBudsSafety() {
        DesktopCoreBridge.StateHolder holder = new DesktopCoreBridge.StateHolder();
        holder.begin("AA:BB:CC:DD:EE:FF", "vivo TWS Air3 Pro", "vivo", 0, 0, 0);
        holder.setConnected(true, null, 0, 0, null);
        holder.featureSpec(FeatureDescriptor.findBuds(Controls.VERIF_UNTESTED, false));
        holder.featureValue(FeatureId.WEAR_STATUS, DesktopCoreBridge.WEAR_NONE);
        assertTrue(DesktopSession.canRingFindBuds(holder.view(), 3, 7, 7), "Confirmed local action allows a valid target");
        for (int wear : new int[] {DesktopCoreBridge.WEAR_LEFT, DesktopCoreBridge.WEAR_RIGHT, DesktopCoreBridge.WEAR_BOTH}) {
            holder.featureValue(FeatureId.WEAR_STATUS, wear);
            assertTrue(!DesktopSession.canRingFindBuds(holder.view(), 3, 7, 7), "Protocol actor blocks current in-ear status " + wear);
        }
        holder.featureValue(FeatureId.WEAR_STATUS, DesktopCoreBridge.WEAR_NONE);
        assertTrue(!DesktopSession.canRingFindBuds(holder.view(), 3, 7, 8), "Dialog cannot carry over to a newer connection");
        assertTrue(!DesktopSession.canRingFindBuds(holder.view(), 4, 7, 7), "Unknown target rejected");
        holder.disconnect();
        assertTrue(!DesktopSession.canRingFindBuds(holder.view(), 3, 7, 7), "Disconnect blocks pending ring");
    }
}

package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.LocalMeshFirewall;
import com.hdz.wearcontrol.LocalMeshProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Desktop Local LAN Mesh node reusing core/'s LocalMeshProtocol and LocalMeshFirewall.
 * Supports both Host mode (share Windows-connected earbuds to phones/watches/glasses)
 * and Client mode (discover UDP 19846 hosts and remote-control earbuds via 4-digit PIN).
 * FIND_MY_BUDS is unconditionally hard-blocked on both ends.
 */
public final class DesktopMeshNode {
    public static final int MODE_OFF = 0;
    public static final int MODE_HOST = 1;
    public static final int MODE_CLIENT = 2;

    private static final int MAX_CLIENTS = 4;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int AUTH_READ_TIMEOUT_MS = 15_000;
    private static final int SESSION_READ_TIMEOUT_MS = 45_000;
    private static final long BEACON_INTERVAL_MS = 3_000L;
    private static final long CLIENT_PING_INTERVAL_MS = 15_000L;
    private static final long DISCOVERY_STALE_MS = 12_000L;
    private static final int CLIENT_SEND_QUEUE_CAP = 32;

    public interface HostCommandSink {
        void onRemoteNoiseMode(int mode);
        void onRemoteRefresh();
        void onRemoteFeature(FeatureId id, int value);
    }

    public interface HostListener {
        default void onHostStarted(String ip, int port, String pin) { }
        default void onHostClientCountChanged(int count) { }
        default void onHostFirewallChanged() { }
        default void onHostStopped() { }
        default void onHostError(String reason) { }
    }

    public static final class DiscoveredPeer {
        public final String ip;
        public final int port;
        public final String hostName;
        public final String deviceName;
        public final boolean connected;
        public final long lastSeenMs;

        public DiscoveredPeer(String ip, int port, String hostName, String deviceName,
                              boolean connected, long lastSeenMs) {
            this.ip = ip;
            this.port = port;
            this.hostName = hostName;
            this.deviceName = deviceName;
            this.connected = connected;
            this.lastSeenMs = lastSeenMs;
        }

        @Override
        public String toString() {
            String dev = deviceName == null || deviceName.isEmpty() ? "未连接耳机" : deviceName;
            return hostName + " (" + ip + ":" + port + ") · " + dev + (connected ? " [在线]" : " [待机]");
        }
    }

    public interface DiscoveryListener {
        void onPeersUpdated(List<DiscoveredPeer> peers);
        default void onDiscoveryError(String reason) { }
    }

    public interface ClientListener {
        default void onClientConnected(String hostName) { }
        default void onClientState(LocalMeshProtocol.StateSnapshot state) { }
        default void onClientDisconnected(String reason) { }
        default void onClientError(String reason) { }
    }

    private final LocalMeshFirewall firewall;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger generation = new AtomicInteger(0);

    private volatile int mode = MODE_OFF;
    private volatile int listenPort = 0;
    private volatile String currentPin = "";
    private volatile String hostLabel;
    private volatile LocalMeshProtocol.StateSnapshot latestSnapshot = LocalMeshProtocol.StateSnapshot.disconnected();

    // Host state
    private volatile ServerSocket hostServerSocket;
    private volatile DatagramSocket beaconSocket;
    private volatile Thread hostAcceptThread;
    private volatile Thread beaconThread;
    private volatile HostCommandSink hostSink;
    private volatile HostListener hostListener;
    private final CopyOnWriteArraySet<HostClientSession> hostClients = new CopyOnWriteArraySet<>();

    // Discovery state
    private volatile boolean discoveryActive;
    private volatile DatagramSocket discoverySocket;
    private volatile Thread discoveryThread;

    // Client state
    private volatile boolean clientAuthed;
    private volatile Socket clientSocket;
    private volatile Thread clientReadThread;
    private volatile Thread clientWriteThread;
    private volatile BlockingQueue<String> clientSendQueue;

    public DesktopMeshNode() {
        this(new LocalMeshFirewall());
    }

    public DesktopMeshNode(LocalMeshFirewall firewall) {
        this.firewall = firewall != null ? firewall : new LocalMeshFirewall();
        this.hostLabel = resolveDefaultHostName();
    }

    public LocalMeshFirewall firewall() {
        return firewall;
    }

    public int mode() {
        return mode;
    }

    public int listenPort() {
        return listenPort;
    }

    public int clientCount() {
        return hostClients.size();
    }

    public boolean isClientConnected() {
        return mode == MODE_CLIENT && clientAuthed;
    }

    public String hostName() {
        return hostLabel;
    }

    public void setHostName(String name) {
        String clean = LocalMeshProtocol.cleanSanitize(name);
        if (!clean.isEmpty()) {
            this.hostLabel = clean;
        }
    }

    public synchronized String pin() {
        if (!LocalMeshProtocol.isValidPin(currentPin)) {
            regeneratePin();
        }
        return currentPin;
    }

    public synchronized void setPin(String pin) {
        if (LocalMeshProtocol.isValidPin(pin)) {
            this.currentPin = pin;
        }
    }

    public synchronized String regeneratePin() {
        String fresh = String.format(Locale.US, "%04d", random.nextInt(10_000));
        this.currentPin = fresh;
        return fresh;
    }

    public String localLanIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                try {
                    if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (!LocalMeshProtocol.isPrivateLanAddress(ip)) continue;
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback != null ? fallback : "127.0.0.1";
    }

    // ---- Host Mode ----

    public void startHost(HostCommandSink sink, HostListener listener) {
        startHost(LocalMeshProtocol.DEFAULT_TCP_PORT, true, sink, listener);
    }

    public synchronized void startHost(int preferredPort, boolean broadcastBeacon,
                                       HostCommandSink sink, HostListener listener) {
        stopAll();
        if (!LocalMeshProtocol.isValidPin(currentPin)) {
            regeneratePin();
        }
        int gen = generation.incrementAndGet();
        this.hostSink = sink;
        this.hostListener = listener;
        this.mode = MODE_HOST;

        Thread accept = new Thread(() -> runHostAcceptor(gen, preferredPort, broadcastBeacon), "WearEars-DesktopMeshHost");
        accept.setDaemon(true);
        this.hostAcceptThread = accept;
        accept.start();
    }

    public void pushSnapshot(LocalMeshProtocol.StateSnapshot snapshot) {
        LocalMeshProtocol.StateSnapshot safe = snapshot == null
                ? LocalMeshProtocol.StateSnapshot.disconnected()
                : snapshot;
        latestSnapshot = safe;
        if (mode != MODE_HOST || hostClients.isEmpty()) return;
        String encoded = LocalMeshProtocol.encodeState(safe);
        for (HostClientSession s : hostClients) {
            s.enqueue(encoded);
        }
    }

    private void runHostAcceptor(int gen, int preferredPort, boolean broadcastBeacon) {
        ServerSocket ss = null;
        try {
            ss = openServerSocket(preferredPort);
            HostListener listener;
            synchronized (this) {
                if (generation.get() != gen || mode != MODE_HOST) return;
                hostServerSocket = ss;
                listenPort = ss.getLocalPort();
                listener = hostListener;
            }
            String ip = localLanIp();
            int port = ss.getLocalPort();
            String pin = pin();
            if (listener != null) listener.onHostStarted(ip, port, pin);
            if (broadcastBeacon) {
                startBeaconThread(gen, port);
            }

            while (generation.get() == gen && mode == MODE_HOST && !ss.isClosed()) {
                Socket client = ss.accept();
                if (generation.get() != gen || mode != MODE_HOST) {
                    closeQuietly(client);
                    break;
                }
                String remoteIp = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "";
                LocalMeshFirewall.Decision decision = firewall.checkIncomingConnection(remoteIp, localLanIp());
                if (!decision.allowed) {
                    notifyFirewallChanged();
                    sendRejectAndClose(client);
                    continue;
                }
                if (hostClients.size() >= MAX_CLIENTS) {
                    sendRejectAndClose(client);
                    continue;
                }
                HostClientSession session = new HostClientSession(gen, client, remoteIp);
                session.start();
            }
        } catch (Throwable t) {
            synchronized (this) {
                if (generation.get() == gen && mode == MODE_HOST) {
                    HostListener hl = hostListener;
                    if (hl != null) {
                        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                        hl.onHostError(msg);
                    }
                    stopAll();
                }
            }
        } finally {
            closeQuietly(ss);
        }
    }

    private ServerSocket openServerSocket(int preferredPort) throws IOException {
        if (preferredPort > 0) {
            try {
                ServerSocket preferred = new ServerSocket();
                preferred.setReuseAddress(true);
                preferred.bind(new InetSocketAddress(preferredPort));
                return preferred;
            } catch (IOException conflict) {
                // fallback to ephemeral port
            }
        }
        ServerSocket fallback = new ServerSocket();
        fallback.setReuseAddress(true);
        fallback.bind(new InetSocketAddress(0));
        return fallback;
    }

    private synchronized void startBeaconThread(int gen, int tcpPort) {
        if (generation.get() != gen || mode != MODE_HOST) return;
        Thread t = new Thread(() -> runBeaconLoop(gen, tcpPort), "WearEars-DesktopMeshBeacon");
        t.setDaemon(true);
        this.beaconThread = t;
        t.start();
    }

    private void runBeaconLoop(int gen, int tcpPort) {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            synchronized (this) {
                if (generation.get() != gen || mode != MODE_HOST) return;
                beaconSocket = sock;
            }
            while (generation.get() == gen && mode == MODE_HOST && !Thread.currentThread().isInterrupted()) {
                try {
                    LocalMeshProtocol.StateSnapshot snap = latestSnapshot;
                    String payload = LocalMeshProtocol.encodeBeacon(
                            tcpPort, hostLabel, snap.deviceName, snap.connected);
                    byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                    broadcastBeaconPacket(sock, data);
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(BEACON_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(sock);
        }
    }

    private void broadcastBeaconPacket(DatagramSocket sock, byte[] data) {
        Set<InetAddress> targets = new HashSet<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (Throwable ignored) {
        }
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                try {
                    if (!nic.isUp() || nic.isLoopback()) continue;
                    for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                        InetAddress bcast = ia.getBroadcast();
                        if (bcast != null) targets.add(bcast);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        for (InetAddress target : targets) {
            try {
                sock.send(new DatagramPacket(data, data.length, target, LocalMeshProtocol.DISCOVERY_UDP_PORT));
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendRejectAndClose(Socket client) {
        Thread reject = new Thread(() -> {
            try {
                client.setSoTimeout(1500);
                LocalMeshProtocol.writeFrame(client.getOutputStream(), LocalMeshProtocol.encodeAuthErr());
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(client);
            }
        }, "WearEars-DesktopMeshReject");
        reject.setDaemon(true);
        reject.start();
    }

    private void notifyHostClientCount() {
        HostListener hl = hostListener;
        if (hl != null) hl.onHostClientCountChanged(hostClients.size());
    }

    private void notifyFirewallChanged() {
        HostListener hl = hostListener;
        if (hl != null) hl.onHostFirewallChanged();
    }

    private final class HostClientSession {
        private final int gen;
        private final Socket socket;
        private final String remoteIp;
        private final BlockingQueue<String> outQueue = new LinkedBlockingQueue<>(CLIENT_SEND_QUEUE_CAP);
        private volatile boolean closed;
        private volatile Thread reader;
        private volatile Thread writer;

        HostClientSession(int gen, Socket socket, String remoteIp) {
            this.gen = gen;
            this.socket = socket;
            this.remoteIp = remoteIp;
        }

        void start() {
            Thread r = new Thread(this::runReadLoop, "WearEars-DesktopMeshHostClient");
            r.setDaemon(true);
            this.reader = r;
            r.start();
        }

        void enqueue(String frame) {
            if (closed || frame == null) return;
            if (!outQueue.offer(frame)) {
                outQueue.poll();
                outQueue.offer(frame);
            }
        }

        void close() {
            closed = true;
            closeQuietly(socket);
            if (writer != null) writer.interrupt();
            if (reader != null) reader.interrupt();
        }

        private void runReadLoop() {
            boolean registered = false;
            try {
                socket.setSoTimeout(AUTH_READ_TIMEOUT_MS);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                boolean authed = false;
                while (!closed && generation.get() == gen && mode == MODE_HOST) {
                    String line = LocalMeshProtocol.readLineBounded(in);
                    if (line == null) return;
                    LocalMeshProtocol.Frame frame = LocalMeshProtocol.parseFrame(line);
                    if (frame != null
                            && frame.type == LocalMeshProtocol.FrameType.HELLO
                            && LocalMeshProtocol.constantTimeEquals(pin(), frame.pin)) {
                        if (hostClients.size() >= MAX_CLIENTS) {
                            LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeAuthErr());
                            return;
                        }
                        firewall.recordAuthSuccess(remoteIp);
                        authed = true;
                        break;
                    }
                    boolean lockedOut = firewall.recordAuthFailure(remoteIp);
                    notifyFirewallChanged();
                    LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeAuthErr());
                    if (lockedOut) {
                        return;
                    }
                }
                if (!authed || closed || generation.get() != gen || mode != MODE_HOST) {
                    return;
                }

                socket.setSoTimeout(SESSION_READ_TIMEOUT_MS);
                hostClients.add(this);
                registered = true;
                notifyHostClientCount();

                LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeAuthOk(hostLabel));
                LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeState(latestSnapshot));

                Thread w = new Thread(() -> runWriteLoop(out), "WearEars-DesktopMeshHostWriter");
                w.setDaemon(true);
                this.writer = w;
                w.start();

                while (!closed && generation.get() == gen && mode == MODE_HOST) {
                    String line = LocalMeshProtocol.readLineBounded(in);
                    if (line == null) break;
                    LocalMeshProtocol.Frame frame = LocalMeshProtocol.parseFrame(line);
                    if (frame == null) {
                        // Also check raw FIND_MY_BUDS attempts so firewall audit records them!
                        if (line.contains("FIND_MY_BUDS")) {
                            LocalMeshFirewall.Decision d = firewall.checkCommand(remoteIp, null);
                            if (!d.allowed) notifyFirewallChanged();
                        }
                        continue;
                    }
                    handleHostFrame(frame);
                }
            } catch (Throwable ignored) {
            } finally {
                closed = true;
                if (registered) {
                    hostClients.remove(this);
                    notifyHostClientCount();
                }
                closeQuietly(socket);
                if (writer != null) writer.interrupt();
            }
        }

        private void runWriteLoop(OutputStream out) {
            try {
                while (!closed && generation.get() == gen && mode == MODE_HOST) {
                    String frame = outQueue.take();
                    LocalMeshProtocol.writeFrame(out, frame);
                }
            } catch (Throwable ignored) {
                close();
            }
        }

        private void handleHostFrame(LocalMeshProtocol.Frame frame) {
            LocalMeshFirewall.Decision decision = firewall.checkCommand(remoteIp, frame);
            if (!decision.allowed) {
                notifyFirewallChanged();
                if (LocalMeshFirewall.REASON_IP_BANNED.equals(decision.reasonCode)) {
                    enqueue(LocalMeshProtocol.encodeAuthErr());
                    close();
                }
                return;
            }
            if (frame.type == LocalMeshProtocol.FrameType.PING) {
                enqueue(LocalMeshProtocol.encodePong());
                return;
            }
            HostCommandSink sink = hostSink;
            if (sink == null) return;
            if (frame.type == LocalMeshProtocol.FrameType.CMD_NOISE) {
                int targetMode = frame.noiseMode;
                if (targetMode >= Controls.MODE_ANC && targetMode <= Controls.MODE_TRANS) {
                    sink.onRemoteNoiseMode(targetMode);
                }
            } else if (frame.type == LocalMeshProtocol.FrameType.CMD_REFRESH) {
                sink.onRemoteRefresh();
            } else if (frame.type == LocalMeshProtocol.FrameType.CMD_FEATURE) {
                FeatureId id = frame.featureId;
                // Secondary hearing-safety hard redline on Host side.
                if (id == null || id == FeatureId.FIND_MY_BUDS || !LocalMeshProtocol.isAllowedRemoteFeature(id)) {
                    return;
                }
                sink.onRemoteFeature(id, frame.featureValue);
            }
        }
    }

    // ---- Client UDP 19846 Discovery ----

    public synchronized void startDiscovery(DiscoveryListener listener) {
        stopDiscovery();
        this.discoveryActive = true;
        Thread t = new Thread(() -> runDiscoveryLoop(listener), "WearEars-DesktopMeshDiscovery");
        t.setDaemon(true);
        this.discoveryThread = t;
        t.start();
    }

    public synchronized void stopDiscovery() {
        discoveryActive = false;
        closeQuietly(discoverySocket);
        discoverySocket = null;
        Thread t = discoveryThread;
        discoveryThread = null;
        if (t != null) t.interrupt();
    }

    private void runDiscoveryLoop(DiscoveryListener listener) {
        DatagramSocket sock = null;
        Thread worker = Thread.currentThread();
        Map<String, DiscoveredPeer> discoveredPeers = new HashMap<>();
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.setBroadcast(true);
            sock.bind(new InetSocketAddress(LocalMeshProtocol.DISCOVERY_UDP_PORT));
            sock.setSoTimeout(2_000);
            synchronized (this) {
                if (discoveryThread != worker) return;
                discoverySocket = sock;
            }
            byte[] buf = new byte[LocalMeshProtocol.MAX_LINE_BYTES];

            while (discoveryActive && discoveryThread == worker && !worker.isInterrupted()) {
                boolean changed = false;
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String senderIp = pkt.getAddress() != null ? pkt.getAddress().getHostAddress() : "";
                    if (LocalMeshProtocol.isPrivateLanAddress(senderIp)) {
                        String raw = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
                        LocalMeshProtocol.Beacon beacon = LocalMeshProtocol.parseBeacon(raw);
                        if (beacon != null) {
                            String key = senderIp + ":" + beacon.tcpPort;
                            discoveredPeers.put(key, new DiscoveredPeer(
                                    senderIp, beacon.tcpPort, beacon.hostName,
                                    beacon.deviceName, beacon.connected, System.currentTimeMillis()));
                            changed = true;
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                }

                long now = System.currentTimeMillis();
                for (String key : new ArrayList<>(discoveredPeers.keySet())) {
                    DiscoveredPeer p = discoveredPeers.get(key);
                    if (p != null && now - p.lastSeenMs > DISCOVERY_STALE_MS) {
                        discoveredPeers.remove(key);
                        changed = true;
                    }
                }
                if (changed && listener != null && discoveryThread == worker) {
                    List<DiscoveredPeer> list = new ArrayList<>(discoveredPeers.values());
                    list.sort(Comparator.comparing((DiscoveredPeer p) -> p.hostName).thenComparing(p -> p.ip));
                    listener.onPeersUpdated(Collections.unmodifiableList(list));
                }
            }
        } catch (IOException | RuntimeException ex) {
            if (discoveryThread == worker && listener != null) listener.onDiscoveryError(ex.toString());
        } finally {
            closeQuietly(sock);
            synchronized (this) {
                if (discoveryThread == worker) {
                    discoveryActive = false;
                    discoverySocket = null;
                    discoveryThread = null;
                }
            }
        }
    }

    // ---- Client Mode ----

    public synchronized void connectClient(String hostIp, int port, String pin, ClientListener listener) {
        stopAll();
        String safeIp = hostIp == null ? "" : hostIp.trim();
        if (!LocalMeshProtocol.isPrivateLanAddress(safeIp)) {
            if (listener != null) listener.onClientError("仅允许连接私有局域网 IP 地址");
            return;
        }
        if (port < 1 || port > 65535) {
            if (listener != null) listener.onClientError("无效端口: " + port);
            return;
        }
        if (!LocalMeshProtocol.isValidPin(pin)) {
            if (listener != null) listener.onClientError("请输入 4 位数字配对码");
            return;
        }

        int gen = generation.incrementAndGet();
        this.mode = MODE_CLIENT;
        this.clientAuthed = false;
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(CLIENT_SEND_QUEUE_CAP);
        this.clientSendQueue = queue;

        Thread t = new Thread(() -> runClientLoop(gen, safeIp, port, pin, listener, queue), "WearEars-DesktopMeshClient");
        t.setDaemon(true);
        this.clientReadThread = t;
        t.start();
    }

    public boolean sendRemoteNoiseMode(int modeVal) {
        if (!isClientConnected()) return false;
        if (modeVal < Controls.MODE_ANC || modeVal > Controls.MODE_TRANS) return false;
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdNoise(modeVal));
    }

    public boolean sendRemoteRefresh() {
        if (!isClientConnected()) return false;
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdRefresh());
    }

    public boolean sendRemoteFeature(FeatureId id, int val) {
        if (!isClientConnected()) return false;
        if (id == null || id == FeatureId.FIND_MY_BUDS || !LocalMeshProtocol.isAllowedRemoteFeature(id)) {
            return false;
        }
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdFeature(id, val));
    }

    private boolean enqueueClientFrame(String frame) {
        BlockingQueue<String> q = clientSendQueue;
        if (q == null || frame == null) return false;
        if (!q.offer(frame)) {
            q.poll();
            return q.offer(frame);
        }
        return true;
    }

    private void runClientLoop(int gen, String hostIp, int port, String pin, ClientListener listener,
                               BlockingQueue<String> queue) {
        Socket sock = new Socket();
        String disconnectReason = "已断开连接";
        try {
            synchronized (this) {
                if (!currentClient(gen)) return;
                clientSocket = sock;
            }
            sock.connect(new InetSocketAddress(hostIp, port), CONNECT_TIMEOUT_MS);
            if (!currentClient(gen)) return;
            sock.setSoTimeout(AUTH_READ_TIMEOUT_MS);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeHello(pin, hostLabel));

            String resp = LocalMeshProtocol.readLineBounded(in);
            LocalMeshProtocol.Frame authFrame = LocalMeshProtocol.parseFrame(resp);
            if (authFrame == null || authFrame.type != LocalMeshProtocol.FrameType.AUTH_OK) {
                disconnectReason = authFrame != null && authFrame.type == LocalMeshProtocol.FrameType.AUTH_ERR
                        ? "配对码错误或被主机防火墙拦截"
                        : "主机认证失败";
                if (currentClient(gen) && listener != null) listener.onClientError(disconnectReason);
                return;
            }

            sock.setSoTimeout(SESSION_READ_TIMEOUT_MS);
            synchronized (this) {
                if (!currentClient(gen)) return;
                clientAuthed = true;
                Thread writer = new Thread(() -> runClientWriteLoop(gen, sock, out, queue), "WearEars-DesktopMeshClientWriter");
                writer.setDaemon(true);
                clientWriteThread = writer;
                writer.start();
            }
            if (listener != null) listener.onClientConnected(authFrame.name);

            while (currentClient(gen)) {
                String line = LocalMeshProtocol.readLineBounded(in);
                if (line == null) {
                    disconnectReason = "主机已关闭共享";
                    break;
                }
                LocalMeshProtocol.Frame f = LocalMeshProtocol.parseFrame(line);
                if (f == null || !currentClient(gen)) continue;
                if (f.type == LocalMeshProtocol.FrameType.STATE && f.state != null) {
                    if (listener != null) listener.onClientState(f.state);
                } else if (f.type == LocalMeshProtocol.FrameType.AUTH_ERR) {
                    disconnectReason = "主机防火墙拒绝访问";
                    if (listener != null) listener.onClientError(disconnectReason);
                    break;
                } else if (f.type == LocalMeshProtocol.FrameType.PING) {
                    enqueueClientFrame(LocalMeshProtocol.encodePong());
                }
            }
        } catch (Throwable t) {
            if (currentClient(gen)) {
                disconnectReason = "连接失败: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                if (listener != null) listener.onClientError(disconnectReason);
            }
        } finally {
            closeQuietly(sock);
            boolean active;
            synchronized (this) {
                active = currentClient(gen);
                if (active) {
                    mode = MODE_OFF;
                    clientAuthed = false;
                    clientSocket = null;
                    clientReadThread = null;
                    if (clientWriteThread != null) clientWriteThread.interrupt();
                    clientWriteThread = null;
                    clientSendQueue = null;
                }
            }
            if (active && listener != null) listener.onClientDisconnected(disconnectReason);
        }
    }

    private boolean currentClient(int gen) {
        return generation.get() == gen && mode == MODE_CLIENT;
    }

    private void runClientWriteLoop(int gen, Socket socket, OutputStream out, BlockingQueue<String> queue) {
        try {
            while (currentClient(gen)) {
                String frame = queue.poll(CLIENT_PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    frame = LocalMeshProtocol.encodePing();
                }
                LocalMeshProtocol.writeFrame(out, frame);
            }
        } catch (Throwable ignored) {
            closeQuietly(socket);
        }
    }

    // ---- Teardown ----

    public synchronized void stopAll() {
        int prevMode = mode;
        generation.incrementAndGet();
        mode = MODE_OFF;
        listenPort = 0;
        clientAuthed = false;

        closeQuietly(hostServerSocket);
        hostServerSocket = null;
        closeQuietly(beaconSocket);
        beaconSocket = null;
        for (HostClientSession s : hostClients) {
            s.close();
        }
        hostClients.clear();
        if (hostAcceptThread != null) hostAcceptThread.interrupt();
        if (beaconThread != null) beaconThread.interrupt();
        hostAcceptThread = null;
        beaconThread = null;

        closeQuietly(clientSocket);
        clientSocket = null;
        if (clientReadThread != null) clientReadThread.interrupt();
        if (clientWriteThread != null) clientWriteThread.interrupt();
        clientReadThread = null;
        clientWriteThread = null;
        clientSendQueue = null;

        if (prevMode == MODE_HOST && hostListener != null) {
            hostListener.onHostStopped();
        }
    }

    private static String resolveDefaultHostName() {
        try {
            String name = System.getenv("COMPUTERNAME");
            if (name == null || name.trim().isEmpty()) {
                name = InetAddress.getLocalHost().getHostName();
            }
            String clean = LocalMeshProtocol.cleanSanitize(name);
            return clean.isEmpty() ? "WearEars-Win" : clean;
        } catch (Throwable ignored) {
            return "WearEars-Win";
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}

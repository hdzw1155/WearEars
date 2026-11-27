package com.hdz.wearcontrol;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Local LAN Mesh runtime for WearEars (SDK 30-34, zero external dependencies).
 * Default MODE_OFF spawns zero threads and opens zero sockets.
 */
public final class LocalMeshManager {
    private static final String TAG = "LocalMeshManager";

    public static final int MODE_OFF = 0;
    public static final int MODE_HOST = 1;
    public static final int MODE_CLIENT = 2;

    private static final int MAX_CLIENTS = 4;
    private static final int MAX_PENDING = 8;
    private static final int MAX_AUTH_FAILURES = 3;
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

    public interface HostStatusListener {
        default void onHostStarted(String ip, int port, String pin) { }
        default void onHostClientCountChanged(int count) { }
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
    }

    public interface DiscoveryListener {
        void onPeersUpdated(List<DiscoveredPeer> peers);
        default void onDiscoveryError() { }
    }

    public interface ClientListener {
        void onClientConnected(String hostName);
        void onClientState(LocalMeshProtocol.StateSnapshot state);
        void onClientDisconnected(String reason);
        void onClientError(String reason);
    }

    private static volatile LocalMeshManager instance;

    public static LocalMeshManager get(Context app) {
        if (instance == null) {
            synchronized (LocalMeshManager.class) {
                if (instance == null) {
                    instance = new LocalMeshManager(app.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context app;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final LocalMeshFirewall firewall = new LocalMeshFirewall();

    // The monitor owns role transitions, socket registration and peer membership.
    private volatile long generation;
    private volatile int mode = MODE_OFF;
    private volatile int discoveryGen;
    private String currentPin = "";
    private HostSession hostSession;
    private ClientSession clientSession;
    private DiscoverySession discoverySession;

    private LocalMeshManager(Context app) {
        this.app = app;
        syncFirewallFromPrefs(app);
    }

    public LocalMeshFirewall firewall() {
        return firewall;
    }

    public synchronized void syncFirewallFromPrefs(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : app;
        if (c == null) return;
        firewall.configure(
                AppPrefs.fwEnabled(c),
                AppPrefs.fwSameSubnetOnly(c),
                AppPrefs.fwAllowlistOnly(c),
                AppPrefs.fwReadOnly(c)
        );
        firewall.setAllowlistFromCsv(AppPrefs.fwAllowlist(c));
        enforceFirewallPolicy();
    }

    public int mode() {
        return mode;
    }

    public synchronized int listenPort() {
        ServerSocket server = hostSession == null ? null : hostSession.serverSocket;
        return server == null ? 0 : Math.max(0, server.getLocalPort());
    }

    public synchronized String pin() {
        String p = currentPin;
        if (!LocalMeshProtocol.isValidPin(p)) {
            p = regeneratePin();
        }
        return p;
    }

    public synchronized String regeneratePin() {
        int val = random.nextInt(10_000);
        String fresh = String.format(java.util.Locale.US, "%04d", val);
        currentPin = fresh;
        return fresh;
    }

    public synchronized int clientCount() {
        return hostSession == null ? 0 : hostSession.hostClients.size();
    }

    public synchronized boolean isClientConnected() {
        return clientSession != null && clientSession.authed;
    }

    public String hostName() {
        try {
            String model = Build.MODEL;
            String clean = LocalMeshProtocol.cleanSanitize(model);
            return clean.isEmpty() ? "WearEars" : clean;
        } catch (Throwable t) {
            return "WearEars";
        }
    }

    /**
     * Resolves a preferred private IPv4 LAN address of this device, falling back to 127.0.0.1.
     */
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
        } catch (Throwable t) {
            Log.w(TAG, "localLanIp lookup failed", t);
        }
        return fallback != null ? fallback : "127.0.0.1";
    }

    // ---- Host lifecycle ----

    public void startHost(HostCommandSink sink) {
        startHost(sink, null);
    }

    public synchronized void startHost(HostCommandSink sink, HostStatusListener statusListener) {
        stopAll();
        syncFirewallFromPrefs(app);
        pin();
        HostSession host = new HostSession(generation, sink, statusListener);
        hostSession = host;
        mode = MODE_HOST;
        host.acceptThread.start();
    }

    private boolean isCurrentHost(HostSession host) {
        return mode == MODE_HOST && generation == host.gen;
    }

    public void pushSnapshot(DeviceState state, boolean live) {
        pushSnapshot(LocalMeshProtocol.StateSnapshot.fromDeviceState(state, live));
    }

    public synchronized void pushSnapshot(LocalMeshProtocol.StateSnapshot snapshot) {
        HostSession host = hostSession;
        if (host == null || !isCurrentHost(host)) return;
        host.snapshot = snapshot == null ? LocalMeshProtocol.StateSnapshot.disconnected() : snapshot;
        if (host.hostClients.isEmpty()) return;
        String encoded = LocalMeshProtocol.encodeState(host.snapshot);
        for (HostClientSession peer : host.hostClients) {
            // The writer first queues the initial snapshot, after sending AUTH_OK.
            if (peer.writer != null && canUseHostClient(peer)) peer.enqueue(encoded);
        }
    }

    /** Applies current network admission rules to authenticated peers without charging a rate slot. */
    public synchronized void enforceFirewallPolicy() {
        HostSession host = hostSession;
        if (host == null || !isCurrentHost(host)) return;
        boolean removed = host.hostClients.removeIf(peer -> {
            if (firewall.allowsPeer(peer.remoteIp, peer.localIp)) return false;
            peer.close();
            return true;
        });
        if (removed) notifyHostClientCount(host);
    }

    private final class HostSession {
        final long gen;
        final HostCommandSink sink;
        final HostStatusListener listener;
        final Thread acceptThread;
        final Set<HostClientSession> pendingHostSockets = new HashSet<>();
        final Set<HostClientSession> hostClients = new HashSet<>();
        volatile LocalMeshProtocol.StateSnapshot snapshot = LocalMeshProtocol.StateSnapshot.disconnected();
        ServerSocket serverSocket;
        DatagramSocket beaconSocket;
        Thread beaconThread;

        HostSession(long gen, HostCommandSink sink, HostStatusListener listener) {
            this.gen = gen;
            this.sink = sink;
            this.listener = listener;
            acceptThread = daemonThread("WearEars-MeshHost", () -> runHostAcceptor(this));
        }

        void close() {
            closeQuietly(serverSocket);
            closeQuietly(beaconSocket);
            for (HostClientSession peer : pendingHostSockets) peer.close();
            for (HostClientSession peer : hostClients) peer.close();
            pendingHostSockets.clear();
            hostClients.clear();
            interruptThread(acceptThread);
            interruptThread(beaconThread);
        }
    }

    private void runHostAcceptor(HostSession host) {
        try (ServerSocket server = openServerSocket(host)) {
            int port = server.getLocalPort();
            String ip = localLanIp();
            synchronized (this) {
                if (!isCurrentHost(host)) return;
                if (host.listener != null) {
                    postForGeneration(host.gen, () -> host.listener.onHostStarted(ip, port, pin()));
                }
                host.beaconThread = daemonThread("WearEars-MeshBeacon", () -> runBeaconLoop(host, port));
                host.beaconThread.start();
            }
            while (isCurrentHost(host)) {
                registerHostClient(host, server.accept());
            }
        } catch (IOException | RuntimeException error) {
            synchronized (this) {
                if (!isCurrentHost(host)) return;
                Log.w(TAG, "Host acceptor stopped with error", error);
                stopAll();
                if (host.listener != null) {
                    postForGeneration(generation, () -> host.listener.onHostError(errorMessage(error)));
                }
            }
        }
    }

    private synchronized void registerHostClient(HostSession host, Socket socket) {
        if (!isCurrentHost(host) || host.pendingHostSockets.size() >= MAX_PENDING
                || host.hostClients.size() >= MAX_CLIENTS) {
            closeQuietly(socket);
            return;
        }
        HostClientSession peer = new HostClientSession(host, socket);
        host.pendingHostSockets.add(peer);
        peer.reader.start();
    }

    private synchronized ServerSocket newHostServerSocket(HostSession host) throws IOException {
        if (!isCurrentHost(host)) throw new IOException("Host stopped");
        ServerSocket server = new ServerSocket();
        host.serverSocket = server;
        return server;
    }

    private ServerSocket openServerSocket(HostSession host) throws IOException {
        ServerSocket ss = newHostServerSocket(host);
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(LocalMeshProtocol.DEFAULT_TCP_PORT));
            return ss;
        } catch (IOException conflict) {
            closeQuietly(ss);
        }

        ss = newHostServerSocket(host);
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(0));
            return ss;
        } catch (IOException | RuntimeException error) {
            closeQuietly(ss);
            throw error;
        }
    }

    private void runBeaconLoop(HostSession host, int tcpPort) {
        try (DatagramSocket socket = new DatagramSocket()) {
            synchronized (this) {
                if (!isCurrentHost(host)) return;
                host.beaconSocket = socket;
            }
            socket.setBroadcast(true);
            while (isCurrentHost(host) && !Thread.currentThread().isInterrupted()) {
                LocalMeshProtocol.StateSnapshot snap = host.snapshot;
                String payload = LocalMeshProtocol.encodeBeacon(
                        tcpPort, hostName(), snap.deviceName, snap.connected);
                broadcastBeaconPacket(socket, payload.getBytes(StandardCharsets.UTF_8));
                Thread.sleep(BEACON_INTERVAL_MS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException error) {
            if (isCurrentHost(host)) Log.w(TAG, "Beacon loop error", error);
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
                DatagramPacket pkt = new DatagramPacket(
                        data, data.length, target, LocalMeshProtocol.DISCOVERY_UDP_PORT);
                sock.send(pkt);
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyHostClientCount(HostSession host) {
        if (!isCurrentHost(host) || host.listener == null) return;
        int count = host.hostClients.size();
        postForGeneration(host.gen, () -> host.listener.onHostClientCountChanged(count));
    }

    // Membership and thread setup are guarded by the manager monitor.
    private boolean canUseHostClient(HostClientSession peer) {
        return isCurrentHost(peer.host) && peer.host.hostClients.contains(peer)
                && firewall.allowsPeer(peer.remoteIp, peer.localIp);
    }

    private synchronized void removeHostClient(HostClientSession peer) {
        peer.close();
        peer.host.pendingHostSockets.remove(peer);
        if (peer.host.hostClients.remove(peer)) notifyHostClientCount(peer.host);
    }

    private final class HostClientSession {
        private final HostSession host;
        private final Socket socket;
        private final String remoteIp;
        private final String localIp;
        private final BlockingQueue<String> outQueue = new LinkedBlockingQueue<>(CLIENT_SEND_QUEUE_CAP);
        private final Thread reader;
        private Thread writer;
        private volatile boolean closed;

        HostClientSession(HostSession host, Socket socket) {
            this.host = host;
            this.socket = socket;
            remoteIp = socket.getInetAddress().getHostAddress();
            localIp = socket.getLocalAddress().getHostAddress();
            reader = daemonThread("WearEars-MeshHostClient", this::runReadLoop);
        }

        void enqueue(String frame) {
            if (!outQueue.offer(frame)) {
                outQueue.poll();
                outQueue.offer(frame);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            closeQuietly(socket);
            outQueue.clear();
            interruptThread(writer);
            interruptThread(reader);
        }

        private void runReadLoop() {
            try {
                LocalMeshFirewall.Decision admission;
                synchronized (LocalMeshManager.this) {
                    if (!isCurrentHost(host) || closed) return;
                    admission = firewall.checkIncomingConnection(remoteIp, localIp);
                }
                if (!admission.allowed) {
                    if (!LocalMeshFirewall.REASON_NON_PRIVATE_IP.equals(admission.reasonCode)) {
                        LocalMeshProtocol.writeFrame(socket.getOutputStream(), LocalMeshProtocol.encodeAuthErr());
                    }
                    return;
                }

                socket.setSoTimeout(AUTH_READ_TIMEOUT_MS);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                if (!authenticate(in, out)) return;
                socket.setSoTimeout(SESSION_READ_TIMEOUT_MS);
                LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeAuthOk(hostName()));
                synchronized (LocalMeshManager.this) {
                    if (!canUseHostClient(this)) return;
                    enqueue(LocalMeshProtocol.encodeState(host.snapshot));
                    writer = daemonThread("WearEars-MeshHostWriter", () -> runWriteLoop(out));
                    writer.start();
                }

                while (!closed && isCurrentHost(host)) {
                    String line = LocalMeshProtocol.readLineBounded(in);
                    if (line == null) break;
                    LocalMeshProtocol.Frame frame = LocalMeshProtocol.parseFrame(line);
                    if (frame == null && line.contains("FIND_MY_BUDS")) {
                        // The parser drops this feature. Still record the attempt at the firewall.
                        frame = LocalMeshProtocol.Frame.cmdFeature(FeatureId.FIND_MY_BUDS, 0);
                    }
                    if (frame != null) handleHostFrame(frame);
                }
            } catch (IOException | RuntimeException ignored) {
            } finally {
                removeHostClient(this);
            }
        }

        private boolean authenticate(InputStream in, OutputStream out) throws IOException {
            for (int failures = 0; failures < MAX_AUTH_FAILURES && !closed && isCurrentHost(host); failures++) {
                String line = LocalMeshProtocol.readLineBounded(in);
                if (line == null) return false;
                LocalMeshProtocol.Frame frame = LocalMeshProtocol.parseFrame(line);
                boolean retry = false;
                synchronized (LocalMeshManager.this) {
                    if (!isCurrentHost(host) || !host.pendingHostSockets.contains(this)) return false;
                    if (firewall.allowsPeer(remoteIp, localIp)) {
                        if (frame != null && frame.type == LocalMeshProtocol.FrameType.HELLO
                                && LocalMeshProtocol.constantTimeEquals(pin(), frame.pin)) {
                            if (host.hostClients.size() < MAX_CLIENTS) {
                                host.pendingHostSockets.remove(this);
                                host.hostClients.add(this);
                                firewall.recordAuthSuccess(remoteIp);
                                notifyHostClientCount(host);
                                return true;
                            }
                        } else {
                            boolean lockedOut = firewall.recordAuthFailure(remoteIp);
                            if (lockedOut) enforceFirewallPolicy();
                            retry = !lockedOut;
                        }
                    }
                }
                LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeAuthErr());
                if (!retry) return false;
            }
            return false;
        }

        private void runWriteLoop(OutputStream out) {
            try {
                while (!closed && isCurrentHost(host)) {
                    String frame = outQueue.take();
                    synchronized (LocalMeshManager.this) {
                        if (!canUseHostClient(this)) return;
                    }
                    LocalMeshProtocol.writeFrame(out, frame);
                }
            } catch (IOException | RuntimeException ignored) {
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                removeHostClient(this);
            }
        }

        private void handleHostFrame(LocalMeshProtocol.Frame frame) {
            synchronized (LocalMeshManager.this) {
                if (!canUseHostClient(this)) return;
                if (!firewall.checkCommand(remoteIp, frame).allowed) {
                    enqueue(LocalMeshProtocol.encodeAuthErr());
                    return;
                }
                switch (frame.type) {
                    case PING:
                        enqueue(LocalMeshProtocol.encodePong());
                        break;
                    case CMD_NOISE:
                    case CMD_REFRESH:
                    case CMD_FEATURE:
                        if (host.sink != null) mainHandler.post(() -> dispatchHostCommand(frame));
                        break;
                    default:
                        break;
                }
            }
        }

        private void dispatchHostCommand(LocalMeshProtocol.Frame frame) {
            synchronized (LocalMeshManager.this) {
                if (!canUseHostClient(this)) return;
                if (!firewall.recheckCommand(remoteIp, frame).allowed) {
                    enqueue(LocalMeshProtocol.encodeAuthErr());
                    return;
                }
                switch (frame.type) {
                    case CMD_NOISE:
                        if (frame.noiseMode >= Controls.MODE_ANC && frame.noiseMode <= Controls.MODE_TRANS) {
                            host.sink.onRemoteNoiseMode(frame.noiseMode);
                        }
                        break;
                    case CMD_REFRESH:
                        host.sink.onRemoteRefresh();
                        break;
                    case CMD_FEATURE:
                        FeatureId id = frame.featureId;
                        // Independent hearing-safety gate before handing off to ControlHub.
                        if (id != null && id != FeatureId.FIND_MY_BUDS && LocalMeshProtocol.isAllowedRemoteFeature(id)) {
                            host.sink.onRemoteFeature(id, frame.featureValue);
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    // ---- Client UDP discovery ----

    public synchronized void startDiscovery(DiscoveryListener listener) {
        stopDiscovery();
        DiscoverySession session = new DiscoverySession(discoveryGen, listener);
        discoverySession = session;
        session.thread.start();
    }

    public synchronized void stopDiscovery() {
        discoveryGen++;
        DiscoverySession session = discoverySession;
        discoverySession = null;
        if (session != null) {
            closeQuietly(session.socket);
            interruptThread(session.thread);
        }
    }

    private final class DiscoverySession {
        final int gen;
        final DiscoveryListener listener;
        final Thread thread;
        final Map<String, DiscoveredPeer> peers = new HashMap<>();
        DatagramSocket socket;

        DiscoverySession(int gen, DiscoveryListener listener) {
            this.gen = gen;
            this.listener = listener;
            thread = daemonThread("WearEars-MeshDiscovery", () -> runDiscoveryLoop(this));
        }
    }

    private void runDiscoveryLoop(DiscoverySession session) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            synchronized (this) {
                if (session.gen != discoveryGen) return;
                session.socket = socket;
            }
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(LocalMeshProtocol.DISCOVERY_UDP_PORT));
            socket.setSoTimeout(2_000);
            byte[] buffer = new byte[LocalMeshProtocol.MAX_LINE_BYTES];

            while (session.gen == discoveryGen && !Thread.currentThread().isInterrupted()) {
                boolean changed = false;
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String ip = packet.getAddress().getHostAddress();
                    if (LocalMeshProtocol.isPrivateLanAddress(ip)) {
                        String text = new String(packet.getData(), packet.getOffset(),
                                packet.getLength(), StandardCharsets.UTF_8);
                        LocalMeshProtocol.Beacon beacon = LocalMeshProtocol.parseBeacon(text);
                        if (beacon != null) {
                            session.peers.put(ip + ":" + beacon.tcpPort, new DiscoveredPeer(
                                    ip, beacon.tcpPort, beacon.hostName, beacon.deviceName,
                                    beacon.connected, System.currentTimeMillis()));
                            changed = true;
                        }
                    }
                } catch (SocketTimeoutException timeout) {
                    // Periodic sweep for stale peers.
                }
                long now = System.currentTimeMillis();
                boolean removed = session.peers.values().removeIf(peer -> now - peer.lastSeenMs > DISCOVERY_STALE_MS);
                if (changed || removed) dispatchPeers(session);
            }
        } catch (IOException | RuntimeException error) {
            if (session.gen == discoveryGen) {
                Log.w(TAG, "Discovery loop error", error);
                if (session.listener != null) postDiscoveryEvent(session, session.listener::onDiscoveryError);
            }
        } finally {
            synchronized (this) {
                if (discoverySession == session) discoverySession = null;
            }
        }
    }

    private void dispatchPeers(DiscoverySession session) {
        if (session.listener == null || session.gen != discoveryGen) return;
        List<DiscoveredPeer> snapshot = new ArrayList<>(session.peers.values());
        snapshot.sort(Comparator.comparing((DiscoveredPeer peer) -> peer.hostName)
                .thenComparing(peer -> peer.ip)
                .thenComparingInt(peer -> peer.port));
        List<DiscoveredPeer> peers = Collections.unmodifiableList(snapshot);
        postDiscoveryEvent(session, () -> session.listener.onPeersUpdated(peers));
    }

    private void postDiscoveryEvent(DiscoverySession session, Runnable callback) {
        mainHandler.post(() -> {
            synchronized (this) {
                if (session.gen == discoveryGen) callback.run();
            }
        });
    }

    // ---- Client TCP session ----

    public synchronized void connectClient(String ip, int port, String pin, ClientListener listener) {
        String invalidReason = clientInputError(ip, port, pin);
        if (invalidReason != null) {
            if (listener != null) postForGeneration(generation, () -> listener.onClientError(invalidReason));
            return;
        }
        stopAll();
        ClientSession session = new ClientSession(generation, ip, port, pin, listener);
        clientSession = session;
        mode = MODE_CLIENT;
        session.reader.start();
    }

    private static String clientInputError(String ip, int port, String pin) {
        if (!LocalMeshProtocol.isPrivateLanAddress(ip)) return "Invalid LAN address";
        if (port < 1 || port > 65535) return "Invalid port";
        if (!LocalMeshProtocol.isValidPin(pin)) return "Invalid 4-digit PIN";
        return null;
    }

    private boolean isCurrentClient(ClientSession session) {
        return mode == MODE_CLIENT && generation == session.gen;
    }

    public boolean sendNoise(int mode) {
        if (mode < Controls.MODE_ANC || mode > Controls.MODE_TRANS) return false;
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdNoise(mode));
    }

    public boolean sendRefresh() {
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdRefresh());
    }

    /** Sends a remote feature write to Host. Hard-blocks FIND_MY_BUDS and disallowed features. */
    public boolean sendFeature(FeatureId id, int val) {
        if (id == null || id == FeatureId.FIND_MY_BUDS || !LocalMeshProtocol.isAllowedRemoteFeature(id)) {
            return false;
        }
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdFeature(id, val));
    }

    private synchronized boolean enqueueClientFrame(String frame) {
        ClientSession session = clientSession;
        return session != null && session.authed && session.sendQueue.offer(frame);
    }

    private final class ClientSession {
        final long gen;
        final ClientListener listener;
        final Socket socket = new Socket();
        final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>(CLIENT_SEND_QUEUE_CAP);
        final Thread reader;
        Thread writer;
        volatile boolean authed;

        ClientSession(long gen, String ip, int port, String pin, ClientListener listener) {
            this.gen = gen;
            this.listener = listener;
            reader = daemonThread("WearEars-MeshClient", () -> runClientSession(this, ip, port, pin));
        }

        void close() {
            authed = false;
            closeQuietly(socket);
            sendQueue.clear();
            interruptThread(writer);
            interruptThread(reader);
        }
    }

    private void runClientSession(ClientSession session, String ip, int port, String pin) {
        Socket socket = session.socket;
        String disconnected = "Disconnected";
        String error = null;
        try {
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(AUTH_READ_TIMEOUT_MS);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeHello(pin, hostName()));
            LocalMeshProtocol.Frame response = LocalMeshProtocol.parseFrame(LocalMeshProtocol.readLineBounded(in));
            if (response == null || response.type != LocalMeshProtocol.FrameType.AUTH_OK) {
                error = response != null && response.type == LocalMeshProtocol.FrameType.AUTH_ERR
                        ? "PIN rejected" : "Handshake failed";
                return;
            }

            socket.setSoTimeout(SESSION_READ_TIMEOUT_MS);
            synchronized (this) {
                if (!isCurrentClient(session)) return;
                session.authed = true;
                postClientEvent(session, () -> session.listener.onClientConnected(response.name));
                session.writer = daemonThread("WearEars-MeshClientWriter", () -> runClientWriter(session, out));
                session.writer.start();
            }

            while (isCurrentClient(session) && !socket.isClosed()) {
                String line = LocalMeshProtocol.readLineBounded(in);
                if (line == null) {
                    disconnected = "Host closed connection";
                    break;
                }
                LocalMeshProtocol.Frame frame = LocalMeshProtocol.parseFrame(line);
                if (frame == null) continue;
                if (frame.type == LocalMeshProtocol.FrameType.STATE && frame.state != null) {
                    postClientEvent(session, () -> session.listener.onClientState(frame.state));
                } else if (frame.type == LocalMeshProtocol.FrameType.PING) {
                    session.sendQueue.offer(LocalMeshProtocol.encodePong());
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (session.authed) {
                disconnected = errorMessage(failure);
            } else {
                error = errorMessage(failure);
            }
        } finally {
            finishClientSession(session, error, disconnected);
        }
    }

    private void runClientWriter(ClientSession session, OutputStream out) {
        try {
            while (isCurrentClient(session) && session.authed) {
                String frame = session.sendQueue.poll(CLIENT_PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (!isCurrentClient(session) || !session.authed) return;
                LocalMeshProtocol.writeFrame(out, frame == null ? LocalMeshProtocol.encodePing() : frame);
            }
        } catch (IOException | RuntimeException ignored) {
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(session.socket);
        }
    }

    private void postClientEvent(ClientSession session, Runnable callback) {
        if (session.listener != null) {
            postForGeneration(session.gen, () -> {
                if (isCurrentClient(session)) callback.run();
            });
        }
    }

    private synchronized void finishClientSession(ClientSession session, String error, String disconnected) {
        session.close();
        if (!isCurrentClient(session)) return;
        clientSession = null;
        mode = MODE_OFF;
        if (session.listener != null) {
            postForGeneration(session.gen, () -> {
                if (error == null) session.listener.onClientDisconnected(disconnected);
                else session.listener.onClientError(error);
            });
        }
    }

    // ---- Full teardown ----

    public synchronized void stopAll() {
        generation++;
        mode = MODE_OFF;
        HostSession host = hostSession;
        hostSession = null;
        ClientSession client = clientSession;
        clientSession = null;

        if (host != null) {
            host.close();
            if (host.listener != null) postForGeneration(generation, host.listener::onHostStopped);
        }
        if (client != null) client.close();
        stopDiscovery();
    }

    private void postForGeneration(long gen, Runnable callback) {
        mainHandler.post(() -> {
            synchronized (this) {
                if (generation == gen) callback.run();
            }
        });
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message != null ? message : error.getClass().getSimpleName();
    }

    private static Thread daemonThread(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void interruptThread(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}

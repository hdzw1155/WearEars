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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger generation = new AtomicInteger(0);

    private volatile int mode = MODE_OFF;
    private volatile int listenPort = 0;
    private volatile String currentPin = "";
    private volatile LocalMeshProtocol.StateSnapshot latestSnapshot = LocalMeshProtocol.StateSnapshot.disconnected();

    // Host state
    private volatile ServerSocket hostServerSocket;
    private volatile DatagramSocket beaconSocket;
    private volatile Thread hostAcceptThread;
    private volatile Thread beaconThread;
    private volatile HostCommandSink hostSink;
    private volatile HostStatusListener hostStatusListener;
    private final CopyOnWriteArraySet<HostClientSession> hostClients = new CopyOnWriteArraySet<>();

    // Discovery state
    private volatile boolean discoveryActive = false;
    private volatile DatagramSocket discoverySocket;
    private volatile Thread discoveryThread;
    private volatile DiscoveryListener discoveryListener;
    private final ConcurrentHashMap<String, DiscoveredPeer> discoveredPeers = new ConcurrentHashMap<>();

    // Client state
    private volatile boolean clientAuthed = false;
    private volatile Socket clientSocket;
    private volatile Thread clientReadThread;
    private volatile Thread clientWriteThread;
    private volatile ClientListener clientListener;
    private volatile BlockingQueue<String> clientSendQueue;

    private LocalMeshManager(Context app) {
        this.app = app;
    }

    public int mode() {
        return mode;
    }

    public int listenPort() {
        return listenPort;
    }

    public String pin() {
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

    public int clientCount() {
        return hostClients.size();
    }

    public boolean isClientConnected() {
        return mode == MODE_CLIENT && clientAuthed;
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
        if (!LocalMeshProtocol.isValidPin(currentPin)) {
            regeneratePin();
        }
        int gen = generation.incrementAndGet();
        this.hostSink = sink;
        this.hostStatusListener = statusListener;
        this.mode = MODE_HOST;

        Thread accept = new Thread(() -> runHostAcceptor(gen), "WearEars-MeshHost");
        accept.setDaemon(true);
        this.hostAcceptThread = accept;
        accept.start();
    }

    public void pushSnapshot(DeviceState state, boolean live) {
        pushSnapshot(LocalMeshProtocol.StateSnapshot.fromDeviceState(state, live));
    }

    public void pushSnapshot(LocalMeshProtocol.StateSnapshot snapshot) {
        LocalMeshProtocol.StateSnapshot safe = snapshot == null
                ? LocalMeshProtocol.StateSnapshot.disconnected()
                : snapshot;
        latestSnapshot = safe;
        if (mode != MODE_HOST || hostClients.isEmpty()) return;
        String encoded = LocalMeshProtocol.encodeState(safe);
        for (HostClientSession session : hostClients) {
            session.enqueue(encoded);
        }
    }

    private void runHostAcceptor(int gen) {
        ServerSocket ss = null;
        try {
            ss = openServerSocket();
            if (generation.get() != gen || mode != MODE_HOST) {
                closeQuietly(ss);
                return;
            }
            hostServerSocket = ss;
            listenPort = ss.getLocalPort();
            String ip = localLanIp();
            int port = listenPort;
            String pin = pin();
            HostStatusListener listener = hostStatusListener;
            if (listener != null) {
                mainHandler.post(() -> listener.onHostStarted(ip, port, pin));
            }
            startBeaconThread(gen, port);

            while (generation.get() == gen && mode == MODE_HOST && !ss.isClosed()) {
                Socket client = ss.accept();
                if (generation.get() != gen || mode != MODE_HOST) {
                    closeQuietly(client);
                    break;
                }
                String remoteIp = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : null;
                if (!LocalMeshProtocol.isPrivateLanAddress(remoteIp)) {
                    closeQuietly(client);
                    continue;
                }
                if (hostClients.size() >= MAX_CLIENTS) {
                    sendRejectAndClose(client);
                    continue;
                }
                HostClientSession session = new HostClientSession(gen, client);
                session.start();
            }
        } catch (Throwable t) {
            if (generation.get() == gen && mode == MODE_HOST) {
                Log.w(TAG, "Host acceptor stopped with error", t);
                HostStatusListener listener = hostStatusListener;
                if (listener != null) {
                    String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    mainHandler.post(() -> listener.onHostError(msg));
                }
                stopAll();
            }
        } finally {
            closeQuietly(ss);
        }
    }

    private ServerSocket openServerSocket() throws IOException {
        try {
            ServerSocket preferred = new ServerSocket();
            preferred.setReuseAddress(true);
            preferred.bind(new InetSocketAddress(LocalMeshProtocol.DEFAULT_TCP_PORT));
            return preferred;
        } catch (IOException conflict) {
            ServerSocket fallback = new ServerSocket();
            fallback.setReuseAddress(true);
            fallback.bind(new InetSocketAddress(0));
            return fallback;
        }
    }

    private void startBeaconThread(int gen, int tcpPort) {
        Thread t = new Thread(() -> runBeaconLoop(gen, tcpPort), "WearEars-MeshBeacon");
        t.setDaemon(true);
        beaconThread = t;
        t.start();
    }

    private void runBeaconLoop(int gen, int tcpPort) {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            beaconSocket = sock;
            while (generation.get() == gen && mode == MODE_HOST && !Thread.currentThread().isInterrupted()) {
                try {
                    LocalMeshProtocol.StateSnapshot snap = latestSnapshot;
                    String payload = LocalMeshProtocol.encodeBeacon(
                            tcpPort, hostName(), snap.deviceName, snap.connected);
                    byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                    broadcastBeaconPacket(sock, data);
                } catch (Throwable sendErr) {
                    Log.w(TAG, "Beacon send failed", sendErr);
                }
                try {
                    Thread.sleep(BEACON_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        } catch (Throwable t) {
            if (generation.get() == gen && mode == MODE_HOST) {
                Log.w(TAG, "Beacon loop error", t);
            }
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
                DatagramPacket pkt = new DatagramPacket(
                        data, data.length, target, LocalMeshProtocol.DISCOVERY_UDP_PORT);
                sock.send(pkt);
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendRejectAndClose(Socket client) {
        Thread reject = new Thread(() -> {
            try {
                client.setSoTimeout(2000);
                LocalMeshProtocol.writeFrame(client.getOutputStream(), LocalMeshProtocol.encodeAuthErr());
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(client);
            }
        }, "WearEars-MeshReject");
        reject.setDaemon(true);
        reject.start();
    }

    private void notifyHostClientCount() {
        HostStatusListener listener = hostStatusListener;
        if (listener != null) {
            int count = hostClients.size();
            mainHandler.post(() -> listener.onHostClientCountChanged(count));
        }
    }

    private final class HostClientSession {
        private final int gen;
        private final Socket socket;
        private final BlockingQueue<String> outQueue = new LinkedBlockingQueue<>(CLIENT_SEND_QUEUE_CAP);
        private volatile boolean closed = false;
        private volatile Thread reader;
        private volatile Thread writer;

        HostClientSession(int gen, Socket socket) {
            this.gen = gen;
            this.socket = socket;
        }

        void start() {
            Thread r = new Thread(this::runReadLoop, "WearEars-MeshHostClient");
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

                // Authenticate via HELLO|<pin>|<clientName>
                int failures = 0;
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
                        authed = true;
                        break;
                    }
                    failures++;
                    LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeAuthErr());
                    if (failures >= MAX_AUTH_FAILURES) {
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

                LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeAuthOk(hostName()));
                LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeState(latestSnapshot));

                Thread w = new Thread(() -> runWriteLoop(out), "WearEars-MeshHostWriter");
                w.setDaemon(true);
                this.writer = w;
                w.start();

                while (!closed && generation.get() == gen && mode == MODE_HOST) {
                    String line = LocalMeshProtocol.readLineBounded(in);
                    if (line == null) break;
                    LocalMeshProtocol.Frame frame = LocalMeshProtocol.parseFrame(line);
                    if (frame == null) continue;
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
            if (frame.type == LocalMeshProtocol.FrameType.PING) {
                enqueue(LocalMeshProtocol.encodePong());
                return;
            }
            HostCommandSink sink = hostSink;
            if (sink == null) return;
            if (frame.type == LocalMeshProtocol.FrameType.CMD_NOISE) {
                int targetMode = frame.noiseMode;
                if (targetMode >= Controls.MODE_ANC && targetMode <= Controls.MODE_TRANS) {
                    mainHandler.post(() -> sink.onRemoteNoiseMode(targetMode));
                }
            } else if (frame.type == LocalMeshProtocol.FrameType.CMD_REFRESH) {
                mainHandler.post(sink::onRemoteRefresh);
            } else if (frame.type == LocalMeshProtocol.FrameType.CMD_FEATURE) {
                FeatureId id = frame.featureId;
                // Secondary hearing-safety hard redline on Host side.
                if (id == null || id == FeatureId.FIND_MY_BUDS || !LocalMeshProtocol.isAllowedRemoteFeature(id)) {
                    return;
                }
                int val = frame.featureValue;
                mainHandler.post(() -> sink.onRemoteFeature(id, val));
            }
        }
    }

    // ---- Client UDP discovery ----

    public synchronized void startDiscovery(DiscoveryListener listener) {
        stopDiscovery();
        this.discoveryListener = listener;
        this.discoveryActive = true;
        this.discoveredPeers.clear();
        Thread t = new Thread(this::runDiscoveryLoop, "WearEars-MeshDiscovery");
        t.setDaemon(true);
        this.discoveryThread = t;
        t.start();
    }

    public synchronized void stopDiscovery() {
        discoveryActive = false;
        discoveryListener = null;
        closeQuietly(discoverySocket);
        discoverySocket = null;
        Thread t = discoveryThread;
        discoveryThread = null;
        if (t != null) t.interrupt();
        discoveredPeers.clear();
    }

    private void runDiscoveryLoop() {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.setBroadcast(true);
            sock.bind(new InetSocketAddress(LocalMeshProtocol.DISCOVERY_UDP_PORT));
            sock.setSoTimeout(2_000);
            discoverySocket = sock;
            byte[] buf = new byte[LocalMeshProtocol.MAX_LINE_BYTES];

            while (discoveryActive && !Thread.currentThread().isInterrupted()) {
                boolean changed = false;
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String senderIp = pkt.getAddress() != null ? pkt.getAddress().getHostAddress() : null;
                    if (LocalMeshProtocol.isPrivateLanAddress(senderIp)) {
                        String text = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
                        LocalMeshProtocol.Beacon beacon = LocalMeshProtocol.parseBeacon(text);
                        if (beacon != null) {
                            long now = System.currentTimeMillis();
                            String key = senderIp + ":" + beacon.tcpPort;
                            discoveredPeers.put(key, new DiscoveredPeer(
                                    senderIp, beacon.tcpPort, beacon.hostName,
                                    beacon.deviceName, beacon.connected, now));
                            changed = true;
                        }
                    }
                } catch (SocketTimeoutException timeout) {
                    // Periodic sweep for stale peers.
                }
                if (pruneStalePeers(System.currentTimeMillis())) {
                    changed = true;
                }
                if (changed) {
                    dispatchPeers();
                }
            }
        } catch (Throwable t) {
            if (discoveryActive) {
                Log.w(TAG, "Discovery loop error", t);
            }
        } finally {
            closeQuietly(sock);
        }
    }

    private boolean pruneStalePeers(long nowMs) {
        boolean removed = false;
        for (String key : new ArrayList<>(discoveredPeers.keySet())) {
            DiscoveredPeer peer = discoveredPeers.get(key);
            if (peer != null && nowMs - peer.lastSeenMs > DISCOVERY_STALE_MS) {
                if (discoveredPeers.remove(key) != null) removed = true;
            }
        }
        return removed;
    }

    private void dispatchPeers() {
        DiscoveryListener listener = discoveryListener;
        if (listener == null || !discoveryActive) return;
        ArrayList<DiscoveredPeer> snapshot = new ArrayList<>(discoveredPeers.values());
        Collections.sort(snapshot, Comparator.comparing((DiscoveredPeer p) -> p.hostName)
                .thenComparing(p -> p.ip)
                .thenComparingInt(p -> p.port));
        List<DiscoveredPeer> unmodifiable = Collections.unmodifiableList(snapshot);
        mainHandler.post(() -> {
            if (discoveryActive) listener.onPeersUpdated(unmodifiable);
        });
    }

    // ---- Client TCP session ----

    public synchronized void connectClient(String ip, int port, String pin, ClientListener listener) {
        if (!LocalMeshProtocol.isPrivateLanAddress(ip)) {
            if (listener != null) mainHandler.post(() -> listener.onClientError("Invalid LAN address"));
            return;
        }
        if (port < 1 || port > 65535) {
            if (listener != null) mainHandler.post(() -> listener.onClientError("Invalid port"));
            return;
        }
        if (!LocalMeshProtocol.isValidPin(pin)) {
            if (listener != null) mainHandler.post(() -> listener.onClientError("Invalid 4-digit PIN"));
            return;
        }

        stopAll();
        int gen = generation.incrementAndGet();
        this.mode = MODE_CLIENT;
        this.clientAuthed = false;
        this.clientListener = listener;
        this.clientSendQueue = new LinkedBlockingQueue<>(CLIENT_SEND_QUEUE_CAP);

        Thread reader = new Thread(() -> runClientSession(gen, ip, port, pin), "WearEars-MeshClient");
        reader.setDaemon(true);
        this.clientReadThread = reader;
        reader.start();
    }

    public boolean sendNoise(int mode) {
        if (this.mode != MODE_CLIENT || !clientAuthed) return false;
        if (mode < Controls.MODE_ANC || mode > Controls.MODE_TRANS) return false;
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdNoise(mode));
    }

    public boolean sendRefresh() {
        if (this.mode != MODE_CLIENT || !clientAuthed) return false;
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdRefresh());
    }

    /**
     * Sends a remote feature write to Host. Hard-blocks FIND_MY_BUDS and disallowed features.
     */
    public boolean sendFeature(FeatureId id, int val) {
        if (this.mode != MODE_CLIENT || !clientAuthed) return false;
        if (id == null || id == FeatureId.FIND_MY_BUDS || !LocalMeshProtocol.isAllowedRemoteFeature(id)) {
            return false;
        }
        return enqueueClientFrame(LocalMeshProtocol.encodeCmdFeature(id, val));
    }

    private boolean enqueueClientFrame(String frame) {
        BlockingQueue<String> queue = clientSendQueue;
        if (queue == null || frame == null) return false;
        return queue.offer(frame);
    }

    private void runClientSession(int gen, String ip, int port, String pin) {
        Socket sock = null;
        String disconnectReason = "Disconnected";
        boolean reportedError = false;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            if (generation.get() != gen || mode != MODE_CLIENT) {
                closeQuietly(sock);
                return;
            }
            clientSocket = sock;
            sock.setSoTimeout(AUTH_READ_TIMEOUT_MS);

            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodeHello(pin, hostName()));
            String respLine = LocalMeshProtocol.readLineBounded(in);
            LocalMeshProtocol.Frame resp = LocalMeshProtocol.parseFrame(respLine);
            if (resp == null || resp.type != LocalMeshProtocol.FrameType.AUTH_OK) {
                reportedError = true;
                String err = (resp != null && resp.type == LocalMeshProtocol.FrameType.AUTH_ERR)
                        ? "PIN rejected"
                        : "Handshake failed";
                postClientError(gen, err);
                return;
            }

            sock.setSoTimeout(SESSION_READ_TIMEOUT_MS);
            clientAuthed = true;
            String remoteHost = resp.name;
            ClientListener listener = clientListener;
            if (listener != null && generation.get() == gen) {
                mainHandler.post(() -> listener.onClientConnected(remoteHost));
            }

            Thread writer = new Thread(() -> runClientWriterAndHeartbeat(gen, out), "WearEars-MeshClientWriter");
            writer.setDaemon(true);
            this.clientWriteThread = writer;
            writer.start();

            while (generation.get() == gen && mode == MODE_CLIENT && !sock.isClosed()) {
                String line = LocalMeshProtocol.readLineBounded(in);
                if (line == null) {
                    disconnectReason = "Host closed connection";
                    break;
                }
                LocalMeshProtocol.Frame frame = LocalMeshProtocol.parseFrame(line);
                if (frame == null) continue;
                if (frame.type == LocalMeshProtocol.FrameType.STATE && frame.state != null) {
                    LocalMeshProtocol.StateSnapshot snap = frame.state;
                    ClientListener l = clientListener;
                    if (l != null && generation.get() == gen) {
                        mainHandler.post(() -> l.onClientState(snap));
                    }
                } else if (frame.type == LocalMeshProtocol.FrameType.PING) {
                    enqueueClientFrame(LocalMeshProtocol.encodePong());
                }
            }
        } catch (Throwable t) {
            if (generation.get() == gen && mode == MODE_CLIENT) {
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                if (!clientAuthed) {
                    reportedError = true;
                    postClientError(gen, msg);
                } else {
                    disconnectReason = msg;
                }
            }
        } finally {
            closeQuietly(sock);
            Thread w = clientWriteThread;
            if (w != null) w.interrupt();
            if (generation.get() == gen) {
                boolean wasClient = (mode == MODE_CLIENT);
                clientAuthed = false;
                clientSocket = null;
                clientWriteThread = null;
                clientReadThread = null;
                clientSendQueue = null;
                mode = MODE_OFF;
                if (wasClient && !reportedError) {
                    ClientListener l = clientListener;
                    String finalReason = disconnectReason;
                    if (l != null) {
                        mainHandler.post(() -> l.onClientDisconnected(finalReason));
                    }
                }
            }
        }
    }

    private void runClientWriterAndHeartbeat(int gen, OutputStream out) {
        BlockingQueue<String> queue = clientSendQueue;
        if (queue == null) return;
        try {
            while (generation.get() == gen && mode == MODE_CLIENT && clientAuthed) {
                String frame = queue.poll(CLIENT_PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (generation.get() != gen || mode != MODE_CLIENT || !clientAuthed) break;
                if (frame == null) {
                    LocalMeshProtocol.writeFrame(out, LocalMeshProtocol.encodePing());
                } else {
                    LocalMeshProtocol.writeFrame(out, frame);
                }
            }
        } catch (Throwable ignored) {
            closeQuietly(clientSocket);
        }
    }

    private void postClientError(int gen, String msg) {
        ClientListener l = clientListener;
        if (l != null && generation.get() == gen) {
            mainHandler.post(() -> l.onClientError(msg));
        }
    }

    // ---- Full teardown ----

    public synchronized void stopAll() {
        int prevMode = mode;
        HostStatusListener prevHostListener = hostStatusListener;

        generation.incrementAndGet();
        mode = MODE_OFF;
        listenPort = 0;
        clientAuthed = false;

        // Tear down Host
        hostSink = null;
        hostStatusListener = null;
        closeQuietly(hostServerSocket);
        hostServerSocket = null;
        closeQuietly(beaconSocket);
        beaconSocket = null;
        for (HostClientSession session : hostClients) {
            session.close();
        }
        hostClients.clear();
        interruptThread(hostAcceptThread);
        hostAcceptThread = null;
        interruptThread(beaconThread);
        beaconThread = null;

        // Tear down Discovery
        stopDiscovery();

        // Tear down Client
        clientListener = null;
        closeQuietly(clientSocket);
        clientSocket = null;
        BlockingQueue<String> q = clientSendQueue;
        clientSendQueue = null;
        if (q != null) q.clear();
        interruptThread(clientWriteThread);
        clientWriteThread = null;
        interruptThread(clientReadThread);
        clientReadThread = null;

        if (prevMode == MODE_HOST && prevHostListener != null) {
            mainHandler.post(prevHostListener::onHostStopped);
        }
    }

    private static void interruptThread(Thread t) {
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {
            }
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

package com.hdz.wearcontrol.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class DesktopInstanceGuard implements AutoCloseable {
    private static final byte[] WAKE = "WAKE\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OK = "OK\n".getBytes(StandardCharsets.US_ASCII);
    // Windows enforces region locks. A second process can still read the port at byte zero.
    private static final long LOCK_POSITION = 4096;
    private static final int WAKE_TIMEOUT_MS = 650;

    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;
    private volatile boolean closed;

    private DesktopInstanceGuard(FileChannel channel, FileLock lock, ServerSocket server) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
    }

    /** Returns null when the resident instance has already acknowledged the wake request. */
    static DesktopInstanceGuard acquire(Path file, Runnable onWake) throws IOException, InterruptedException {
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        FileChannel channel = FileChannel.open(absolute, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        boolean ownsChannel = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            do {
                FileLock lock = tryLock(channel);
                if (lock != null) {
                    DesktopInstanceGuard guard = listen(channel, lock, onWake);
                    ownsChannel = true;
                    return guard;
                }
                if (wake(readPort(absolute))) return null;
                Thread.sleep(75);
            } while (System.nanoTime() - deadline < 0);
            throw new IOException("WearEars holds desktop.lock but did not acknowledge WAKE");
        } finally {
            if (!ownsChannel) channel.close();
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock(LOCK_POSITION, 1, false);
        } catch (OverlappingFileLockException alreadyHeld) {
            return null;
        }
    }

    private static DesktopInstanceGuard listen(FileChannel channel, FileLock lock, Runnable onWake) throws IOException {
        ServerSocket server = new ServerSocket(0, 4, loopback());
        try {
            ByteBuffer port = StandardCharsets.US_ASCII.encode(String.format(Locale.ROOT, "%05d\n", server.getLocalPort()));
            channel.position(0);
            while (port.hasRemaining()) channel.write(port);
            channel.force(true);
            DesktopInstanceGuard guard = new DesktopInstanceGuard(channel, lock, server);
            Thread receiver = new Thread(() -> guard.receive(onWake), "WearEars-DesktopWake");
            receiver.setDaemon(true);
            receiver.start();
            return guard;
        } catch (IOException | RuntimeException ex) {
            server.close();
            throw ex;
        }
    }

    static int readPort(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] line = in.readNBytes(6);
            String text = new String(line, StandardCharsets.US_ASCII);
            int newline = text.indexOf('\n');
            return parsePort(newline >= 0 ? text.substring(0, newline) : text);
        } catch (IOException ex) {
            return -1;
        }
    }

    static int parsePort(String value) {
        if (value == null) return -1;
        String digits = value.trim();
        if (!digits.matches("[0-9]{1,5}")) return -1;
        int port = Integer.parseInt(digits);
        return port >= 1 && port <= 65535 ? port : -1;
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    }

    private static boolean wake(int port) {
        if (port <= 0) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(loopback(), port), WAKE_TIMEOUT_MS);
            socket.setSoTimeout(WAKE_TIMEOUT_MS);
            socket.getOutputStream().write(WAKE);
            return Arrays.equals(OK, socket.getInputStream().readNBytes(OK.length));
        } catch (IOException ex) {
            return false;
        }
    }

    private void receive(Runnable onWake) {
        while (!closed) {
            Socket peer;
            try {
                peer = server.accept();
            } catch (IOException ex) {
                if (!closed) System.err.println("[WearEars] Wake listener failed: " + ex);
                return;
            }
            try (Socket socket = peer) {
                socket.setSoTimeout(WAKE_TIMEOUT_MS);
                if (!closed && Arrays.equals(WAKE, socket.getInputStream().readNBytes(WAKE.length))) {
                    onWake.run();
                    socket.getOutputStream().write(OK);
                }
            } catch (IOException ex) {
                // A timed-out or abandoned local wake request leaves the listener available.
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { server.close(); } catch (IOException ex) { System.err.println(ex); }
        try { lock.release(); } catch (IOException ex) { System.err.println(ex); }
        try { channel.close(); } catch (IOException ex) { System.err.println(ex); }
    }
}

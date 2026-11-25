package com.hdz.wearcontrol;

import java.io.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one dial/read thread and one bounded writer. close() can run while connect() blocks. */
final class RfcommLink implements AutoCloseable {
    interface Socket extends Closeable {
        void connect() throws IOException;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
    }
    interface Sockets { Socket create(UUID service, boolean insecure, int channel) throws Exception; }
    interface Events {
        boolean post(Runnable work);
        void after(Runnable work, long delay);
        void cancel(Runnable work);
        void opened();
        void receive(byte[] bytes);
        void unavailable();
        void lost(String reason);
    }

    // A stalled SDP attempt must leave time for the remaining services and fixed channels.
    static final long ATTEMPT_TIMEOUT_MS = 4000;
    private final Sockets sockets;
    private final Events events;
    private final ProtocolWriter writer = new ProtocolWriter();
    private final Semaphore readSlots = new Semaphore(8);
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean closed;
    private volatile OutputStream output;
    private Socket candidate;
    private boolean dialing;
    private Thread io;

    RfcommLink(Sockets sockets, Events events) { this.sockets = sockets; this.events = events; }
    synchronized void start(UUID[] services, int[] channels) {
        if (closed || io != null) return;
        writer.start();
        io = new Thread(() -> {
            for (UUID service : services) {
                if (dial(service, false, 0) || dial(service, true, 0)) return;
            }
            for (int channel : channels) if (dial(null, false, channel)) return;
            deliver(events::unavailable);
        }, "WearEars-RFCOMM");
        io.start();
    }
    private boolean dial(UUID service, boolean insecure, int channel) {
        if (closed) return true;
        final Socket attempt;
        try {
            attempt = sockets.create(service, insecure, channel);
        } catch (Exception unavailable) {
            return closed;
        }
        synchronized (this) {
            if (closed) { closeSocket(attempt); return true; }
            candidate = attempt;
            dialing = true;
        }
        Runnable timeout = () -> expire(attempt);
        InputStream stream;
        try {
            events.after(timeout, ATTEMPT_TIMEOUT_MS);
            attempt.connect();
            stream = attempt.input();
            OutputStream connectedOutput = attempt.output();
            synchronized (this) {
                if (closed || candidate != attempt) return closed;
                dialing = false;
                output = connectedOutput;
            }
        } catch (Exception failure) {
            expire(attempt);
            return closed;
        } finally {
            events.cancel(timeout);
        }
        deliver(events::opened);
        read(stream);
        return true;
    }
    private void expire(Socket attempt) {
        synchronized (this) {
            if (!dialing || candidate != attempt) return;
            dialing = false;
            candidate = null;
        }
        closeSocket(attempt);
    }
    private void read(InputStream stream) {
        byte[] buffer = new byte[512];
        try {
            while (!closed) {
                int n = stream.read(buffer);
                if (n < 0) break;
                if (n == 0) continue;
                readSlots.acquire();
                byte[] copy = Arrays.copyOf(buffer, n);
                boolean queued = events.post(() -> {
                    try { if (!closed) events.receive(copy); }
                    finally { readSlots.release(); }
                });
                if (!queued) { readSlots.release(); return; }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            lost(failure.toString());
            return;
        }
        lost("RFCOMM closed");
    }
    void send(byte[] data, Runnable flushed) {
        if (closed || failed.get()) return;
        final byte[] frame = data.clone();
        final OutputStream stream = output;
        if (stream == null || !writer.post(() -> {
            try {
                if (closed || failed.get()) return;
                stream.write(frame);
                stream.flush();
                if (flushed != null) deliver(flushed);
            } catch (IOException | RuntimeException failure) { lost(failure.toString()); }
        })) lost("RFCOMM writer unavailable");
    }
    private void lost(String reason) {
        if (closed || !failed.compareAndSet(false, true)) return;
        writer.stop();
        deliver(() -> events.lost(reason));
    }
    private void deliver(Runnable task) { if (!closed) events.post(() -> { if (!closed) task.run(); }); }
    @Override public void close() {
        Socket socket;
        synchronized (this) {
            if (closed) return;
            closed = true;
            socket = candidate;
            candidate = null;
            output = null;
            if (io != null) io.interrupt();
        }
        writer.stop();
        closeSocket(socket);
    }
    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException | RuntimeException ignored) { }
    }
}

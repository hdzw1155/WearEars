package com.hdz.wearcontrol;

import java.util.ArrayDeque;
import java.util.Arrays;

/** All methods run on the session actor. A successful stack callback releases one fragment. */
final class GattPump {
    interface Port {
        boolean start(byte[] fragment);
        void later(Runnable action, long ms);
        void cancel(Runnable action);
        void fail(String reason);
    }
    private static final int CHUNK = 20, LIMIT = 32;
    private static final long WRITE_TIMEOUT = 4000;
    private static final class Frame {
        final byte[] bytes;
        final Runnable complete;
        int offset;
        Frame(byte[] bytes, Runnable complete) { this.bytes = bytes.clone(); this.complete = complete; }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final Port port;
    private final int gap;
    private final boolean paceTail;
    private boolean ready, closed, inFlight, pacing;
    private final Runnable timeout = () -> abort("GATT write callback timed out");
    private final Runnable resume = () -> { pacing = false; advance(); };

    GattPump(Port port, int gap, boolean paceTail) { this.port = port; this.gap = gap; this.paceTail = paceTail; }
    void open() { if (!closed) { ready = true; advance(); } }
    void send(byte[] data, Runnable complete) {
        if (closed) return;
        if (!ready || data.length == 0 || frames.size() >= LIMIT) { abort("GATT write queue unavailable"); return; }
        frames.add(new Frame(data, complete));
        advance();
    }
    private void advance() {
        if (!ready || closed || inFlight || pacing || frames.isEmpty()) return;
        Frame frame = frames.element();
        if (frame.offset == frame.bytes.length) {
            frames.remove();
            if (frame.complete != null) frame.complete.run();
            advance();
            return;
        }
        int end = Math.min(frame.offset + CHUNK, frame.bytes.length);
        byte[] chunk = Arrays.copyOfRange(frame.bytes, frame.offset, end);
        frame.offset = end;
        inFlight = true;
        port.later(timeout, WRITE_TIMEOUT);
        if (!port.start(chunk)) abort("GATT rejected fragment");
    }
    void acknowledged(boolean success) {
        if (closed || !inFlight) return;
        inFlight = false;
        port.cancel(timeout);
        if (!success) { abort("GATT write failed"); return; }
        Frame frame = frames.element();
        if (gap > 0 && (frame.offset < frame.bytes.length || paceTail)) {
            pacing = true;
            port.later(resume, gap);
        } else advance();
    }
    void close() {
        closed = true;
        frames.clear();
        port.cancel(timeout);
        port.cancel(resume);
    }
    private void abort(String reason) {
        if (closed) return;
        close();
        port.fail(reason);
    }
}

package com.hdz.wearcontrol;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the production socket lifecycle without Android, radios, or elapsed-time sleeps. */
public final class RfcommChecks {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    private static final long DEADLOCK_GUARD = 4;
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(DEADLOCK_GUARD, TimeUnit.SECONDS)) throw new AssertionError("Worker stalled");
    }
    private static <T> T next(BlockingQueue<T> queue) throws InterruptedException {
        T result = queue.poll(DEADLOCK_GUARD, TimeUnit.SECONDS);
        if (result == null) throw new AssertionError("Expected queued work");
        return result;
    }

    private static final class Actor implements RfcommLink.Events {
        final BlockingQueue<Runnable> inbox = new LinkedBlockingQueue<>();
        final BlockingQueue<Runnable> timers = new LinkedBlockingQueue<>();
        final List<Integer> bytes = new ArrayList<>();
        int opens, losses, fallbacks;
        public boolean post(Runnable r) { return inbox.offer(r); }
        public void after(Runnable r, long ms) {
            if (ms != RfcommLink.ATTEMPT_TIMEOUT_MS || ms <= 0) throw new AssertionError("Invalid timer");
            timers.add(r);
        }
        public void cancel(Runnable r) { timers.remove(r); }
        public void opened() { opens++; }
        public void unavailable() { fallbacks++; }
        public void receive(byte[] data) { for (byte b : data) bytes.add(b & 255); }
        public void lost(String reason) { losses++; }
        void pump() throws InterruptedException { next(inbox).run(); }
        void drain() { Runnable r; while ((r = inbox.poll()) != null) r.run(); }
    }

    private static class Peer implements RfcommLink.Socket {
        final CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1);
        final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        final boolean blockConnect;
        InputStream reader = new InputStream() {
            public int read() { throw new AssertionError("Expected buffered read"); }
            public int read(byte[] dst) throws IOException {
                try {
                    byte[] chunk = incoming.take();
                    if (chunk.length == 0) return -1;
                    System.arraycopy(chunk, 0, dst, 0, chunk.length);
                    return chunk.length;
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        };
        OutputStream writer = new ByteArrayOutputStream();
        Peer(boolean blockConnect) { this.blockConnect = blockConnect; }
        public void connect() throws IOException {
            entered.countDown();
            if (!blockConnect) return;
            while (closed.getCount() != 0) {
                // Like BluetoothSocket.connect on some platforms: interrupt alone has no effect.
                try { closed.await(); } catch (InterruptedException ignored) { }
            }
            throw new IOException("Peer closed during connect");
        }
        public InputStream input() { return reader; }
        public OutputStream output() { return writer; }
        public void close() { closed.countDown(); incoming.offer(new byte[0]); }
    }

    private static final class SlowOutput extends OutputStream {
        final CountDownLatch flushing = new CountDownLatch(1), allow = new CountDownLatch(1), exited = new CountDownLatch(1);
        final List<Integer> frames = new CopyOnWriteArrayList<>();
        boolean reject;
        public void write(int value) { throw new AssertionError("Expected frame write"); }
        public void write(byte[] frame) { frames.add(frame[0] & 255); }
        public void flush() throws IOException {
            flushing.countDown();
            try {
                allow.await();
                if (reject) throw new IOException("Write failed");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            finally { exited.countDown(); }
        }
    }

    private static void endpoints() throws Exception {
        Actor actor = new Actor();
        List<String> tries = new CopyOnWriteArrayList<>();
        List<String> expected = Arrays.asList("A secure", "A insecure", "B secure", "B insecure", "13", "16");
        Peer peer = new Peer(false);
        try (RfcommLink link = new RfcommLink((uuid, insecure, channel) -> {
            tries.add(channel > 0 ? String.valueOf(channel) : (A.equals(uuid) ? "A" : "B") + (insecure ? " insecure" : " secure"));
            if (channel != 16) throw new IOException("Endpoint unavailable");
            return peer;
        }, actor)) {
            link.start(new UUID[]{A, B}, new int[]{13, 16});
            actor.pump();
            check(tries.equals(expected), "Secure then insecure SDP, then fixed channels in declared order");
            check(actor.opens == 1 && actor.fallbacks == 0 && actor.timers.isEmpty(), "Exactly one open and no remaining timer");
            peer.incoming.add(new byte[]{4}); peer.incoming.add(new byte[]{5,6});
            actor.pump(); actor.pump();
            check(actor.bytes.equals(Arrays.asList(4,5,6)), "Partial reads keep order");
            peer.incoming.add(new byte[0]); actor.pump();
            check(actor.losses == 1 && actor.fallbacks == 0, "EOF closes the established session");
        }
        check(peer.closed.getCount() == 0, "Live socket released");
        Actor missing = new Actor();
        try (RfcommLink link = new RfcommLink((u,i,c) -> { throw new IOException("Missing"); }, missing)) {
            link.start(new UUID[]{A}, new int[]{1,16});
            missing.pump();
            check(missing.fallbacks == 1 && missing.opens == 0 && missing.losses == 0, "Exhaustion opens fallback exactly once");
        }
    }

    private static void stalledConnect() throws Exception {
        Actor actor = new Actor();
        Peer stalled = new Peer(true), good = new Peer(false);
        AtomicInteger created = new AtomicInteger();
        try (RfcommLink link = new RfcommLink((u,i,c) -> created.getAndIncrement() == 0 ? stalled : good, actor)) {
            link.start(new UUID[]{A}, new int[0]);
            await(stalled.entered);
            Runnable deadline = next(actor.timers);
            deadline.run();
            await(stalled.closed); actor.pump();
            check(created.get() == 2 && actor.opens == 1 && good.closed.getCount() != 0,
                    "Closing the stalled candidate reaches the next SDP endpoint");
            deadline.run();
            check(good.closed.getCount() != 0 && actor.losses == 0, "Obsolete deadline cannot close the replacement");
        }
        Actor old = new Actor();
        Peer blocked = new Peer(true);
        AtomicInteger attempts = new AtomicInteger();
        RfcommLink link = new RfcommLink((u,i,c) -> { attempts.incrementAndGet(); return blocked; }, old);
        try {
            link.start(new UUID[]{A}, new int[]{13});
            await(blocked.entered);
            Runnable timeout = next(old.timers);
            link.close(); link.close(); timeout.run();
            await(blocked.closed);
            old.drain();
            check(attempts.get() == 1 && old.opens + old.losses + old.fallbacks == 0, "Cancellation cannot start fallback or signal the caller");
        } finally { link.close(); }
    }

    private static void boundedReceive() throws Exception {
        Actor actor = new Actor();
        CountDownLatch ninthRead = new CountDownLatch(1), tenthRead = new CountDownLatch(1);
        Peer peer = new Peer(false);
        AtomicInteger reads = new AtomicInteger();
        peer.reader = new InputStream() {
            public int read() { throw new AssertionError("Expected buffered read"); }
            public int read(byte[] data) {
                int i = reads.incrementAndGet();
                if (i == 9) ninthRead.countDown();
                if (i == 10) tenthRead.countDown();
                data[0] = (byte) i;
                return 1;
            }
        };
        try (RfcommLink link = new RfcommLink((u,i,c) -> peer, actor)) {
            link.start(new UUID[]{A}, new int[0]); actor.pump();
            await(ninthRead);
            check(reads.get() == 9 && actor.inbox.size() == 8, "Eight queued chunks apply backpressure; ninth waits in the read thread");
            actor.pump(); await(tenthRead);
            check(actor.bytes.equals(Arrays.asList(1)) && actor.inbox.size() == 8, "Releasing one slot admits exactly one more read");
            link.close(); actor.drain();
            check(actor.bytes.equals(Arrays.asList(1)) && actor.losses == 0, "Queued old data ignored after close");
        }
    }

    private static void slowWriter() throws Exception {
        Actor actor = new Actor();
        Peer peer = new Peer(false);
        SlowOutput out = new SlowOutput();
        peer.writer = out;
        AtomicInteger callbacks = new AtomicInteger();
        try (RfcommLink link = new RfcommLink((u,i,c) -> peer, actor)) {
            link.start(new UUID[]{A}, new int[0]); actor.pump();
            link.send(new byte[]{1}, callbacks::incrementAndGet);
            await(out.flushing);
            byte[] second = {2};
            link.send(second, callbacks::incrementAndGet); second[0] = 9;
            check(out.frames.equals(Arrays.asList(1)) && actor.inbox.isEmpty(), "Queued frame waits for previous flush");
            out.allow.countDown();
            actor.pump(); actor.pump();
            check(out.frames.equals(Arrays.asList(1,2)) && callbacks.get() == 2, "Writes preserve order, clone caller data and report actual flush");
        } finally { out.allow.countDown(); }

        for (boolean fail : new boolean[]{false,true}) {
            Actor old = new Actor();
            Peer oldPeer = new Peer(false);
            SlowOutput blocked = new SlowOutput();
            oldPeer.writer = blocked;
            try (RfcommLink link = new RfcommLink((u,i,c) -> oldPeer, old)) {
                link.start(new UUID[]{A}, new int[0]); old.pump();
                link.send(new byte[]{3}, callbacks::incrementAndGet); await(blocked.flushing);
                link.send(new byte[]{4}, callbacks::incrementAndGet);
                if (fail) {
                    blocked.reject = true; blocked.allow.countDown(); old.pump();
                    check(old.losses == 1, "Write failure reports once");
                } else link.close();
                blocked.allow.countDown(); await(blocked.exited); old.drain();
                check(blocked.frames.equals(Arrays.asList(3)) && callbacks.get() == 2,
                        "Queued write and completion invalidated after terminal failure or close");
            } finally { blocked.allow.countDown(); }
        }
    }

    public static void main(String[] args) throws Exception {
        endpoints(); stalledConnect(); boundedReceive(); slowWriter();
        System.out.println("PASS " + checks + " RFCOMM transport checks");
    }
}

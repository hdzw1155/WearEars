package android.os;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Deterministic actor queue used by platform callback fixtures. */
public class Handler {
    private static final class Job {
        final long due, order;
        final Runnable work;
        Job(long due, long order, Runnable work) { this.due = due; this.order = order; this.work = work; }
    }
    private final PriorityQueue<Job> jobs = new PriorityQueue<>(
            Comparator.<Job>comparingLong(j -> j.due).thenComparingLong(j -> j.order));
    private final Handler delegate;
    private long now, sequence;

    public Handler() { this.delegate = null; }
    public Handler(Looper looper) { this.delegate = looper == null ? null : looper.handler(); }

    public boolean post(Runnable action) { return postDelayed(action, 0); }
    public boolean postDelayed(Runnable action, long delay) {
        if (delegate != null) return delegate.postDelayed(action, delay);
        jobs.add(new Job(now + delay, sequence++, action));
        return true;
    }
    public void removeCallbacks(Runnable action) {
        if (delegate != null) { delegate.removeCallbacks(action); return; }
        jobs.removeIf(j -> j.work == action);
    }
    public void advance(long millis) {
        if (delegate != null) { delegate.advance(millis); return; }
        long end = now + millis;
        int budget = 2000;
        while (!jobs.isEmpty() && jobs.peek().due <= end) {
            if (--budget == 0) throw new AssertionError("Unbounded actor queue");
            Job job = jobs.remove();
            now = job.due;
            job.work.run();
        }
        now = end;
    }
}

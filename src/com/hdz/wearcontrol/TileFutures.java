package com.hdz.wearcontrol;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;

/** 由 FutureTask 处理取消和异常，只补充非阻塞完成监听。 */
final class TileFutures {
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread t = new Thread(task, "tile-render"); t.setDaemon(true); return t;
        }
    });
    static <T> ListenableFuture<T> immediate(final T value) {
        Task<T> task = new Task<T>(new Callable<T>() { @Override public T call() { return value; } });
        task.run(); return task;
    }
    static <T> ListenableFuture<T> submit(Callable<T> callable) {
        Task<T> task = new Task<T>(callable); BG.execute(task); return task;
    }
    private static final class Task<T> extends FutureTask<T> implements ListenableFuture<T> {
        private final List<Runnable> listeners = new ArrayList<Runnable>();
        private boolean dispatched;
        Task(Callable<T> callable) { super(callable); }
        @Override public void addListener(final Runnable listener, final Executor executor) {
            if (listener == null || executor == null) throw new NullPointerException();
            Runnable delivery = new Runnable() { @Override public void run() {
                try { executor.execute(listener); } catch (RuntimeException ignored) { }
            }};
            synchronized (listeners) {
                if (!dispatched) { listeners.add(delivery); return; }
            }
            delivery.run();
        }
        @Override protected void done() {
            List<Runnable> copy;
            synchronized (listeners) {
                dispatched = true; copy = new ArrayList<Runnable>(listeners); listeners.clear();
            }
            for (Runnable listener : copy) listener.run();
        }
    }
    private TileFutures() { }
}

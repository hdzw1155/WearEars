package com.hdz.wearcontrol;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 磁贴用的小号 ListenableFuture 工具。
 *
 * <p>工程里只有 listenablefuture 的接口 jar(<b>没有 Guava 的 Futures</b>), 所以自己实现:
 * <ul>
 *   <li>{@link #immediate} — 结果已就绪(布局构建这种毫秒级活), 同步返回;</li>
 *   <li>{@link #submit} — 丢给后台单线程算(电量环要建 Bitmap、编 PNG), 不占系统 Binder 线程。</li>
 * </ul>
 *
 * <p><b>通知契约(踩过的坑):</b> addListener 里不能阻塞等结果。tiles-1.1.0 传进来的
 * Executor 是把 Runnable post 到主线程的, 如果在这里 get() 等 PNG 编完, 主线程就陪着干等,
 * 主界面在前台时会顿一下。所以完成时由后台线程把 listener 派发给调用方给的 Executor,
 * addListener 自己立刻返回。
 */
final class TileFutures {

    private static final ExecutorService BG = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tile-render");
            t.setDaemon(true);
            return t;
        }
    });

    private TileFutures() {
    }

    static <T> ListenableFuture<T> immediate(T value) {
        return new ImmediateFuture<T>(value);
    }

    static <T> ListenableFuture<T> submit(final Callable<T> task) {
        final TaskFuture<T> future = new TaskFuture<T>();
        BG.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    future.complete(task.call());
                } catch (Throwable t) {
                    // 连 Error 也接住: 后台任务失败就是资源表缺图, 不能让整个进程倒掉
                    future.failed(t);
                }
            }
        });
        return future;
    }

    /** 已就绪的结果: 不需要锁, 只实现契约 */
    private static final class ImmediateFuture<T> implements ListenableFuture<T> {
        private final T value;

        ImmediateFuture(T value) {
            this.value = value;
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            // 已就绪也要按契约在调用方给的 Executor 上跑, 不在调用线程直接 run
            executor.execute(listener);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public T get(long timeout, TimeUnit unit) {
            return value;
        }
    }

    /** 后台任务: 自己维护完成状态与监听者, 完成时通知, 绝不在 addListener 里等待 */
    private static final class TaskFuture<T> implements ListenableFuture<T> {
        private final Object lock = new Object();
        private final List<ListenerEntry> listeners = new ArrayList<ListenerEntry>(2);

        private T value;
        private Throwable error;
        private boolean done;
        private boolean cancelled;

        void complete(T result) {
            List<ListenerEntry> toNotify;
            synchronized (lock) {
                if (done) return;
                value = result;
                done = true;
                toNotify = drainLocked();
                lock.notifyAll();
            }
            dispatch(toNotify);
        }

        void failed(Throwable t) {
            List<ListenerEntry> toNotify;
            synchronized (lock) {
                if (done) return;
                error = t;
                done = true;
                toNotify = drainLocked();
                lock.notifyAll();
            }
            dispatch(toNotify);
        }

        private List<ListenerEntry> drainLocked() {
            if (listeners.isEmpty()) return null;
            List<ListenerEntry> copy = new ArrayList<ListenerEntry>(listeners);
            listeners.clear();
            return copy;
        }

        private static void dispatch(List<ListenerEntry> entries) {
            if (entries == null) return;
            for (int i = 0; i < entries.size(); i++) {
                entries.get(i).notifyDone();
            }
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            ListenerEntry entry = new ListenerEntry(listener, executor);
            boolean ready;
            synchronized (lock) {
                ready = done;
                if (!ready) {
                    listeners.add(entry);
                }
            }
            if (ready) {
                entry.notifyDone();
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (lock) {
                if (done) return false;
                cancelled = true;
                done = true;
                lock.notifyAll();
                return true;
            }
        }

        @Override
        public boolean isCancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }

        @Override
        public boolean isDone() {
            synchronized (lock) {
                return done;
            }
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            synchronized (lock) {
                while (!done) {
                    lock.wait();
                }
                return resultLocked();
            }
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            synchronized (lock) {
                while (!done) {
                    long remain = deadline - System.nanoTime();
                    if (remain <= 0L) {
                        throw new TimeoutException("tile render timeout");
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, remain);
                }
                return resultLocked();
            }
        }

        private T resultLocked() throws ExecutionException {
            if (cancelled) {
                throw new CancellationException();
            }
            if (error != null) {
                throw new ExecutionException(error);
            }
            return value;
        }
    }

    /** 监听者 = 回调 + 调用方指定的执行器(通常是 post 到主线程的 Handler 包装) */
    private static final class ListenerEntry {
        private final Runnable listener;
        private final Executor executor;

        ListenerEntry(Runnable listener, Executor executor) {
            this.listener = listener;
            this.executor = executor;
        }

        void notifyDone() {
            try {
                executor.execute(listener);
            } catch (RuntimeException ignored) {
                // 进程正在退出/执行器拒绝时不影响其它监听者
            }
        }
    }
}

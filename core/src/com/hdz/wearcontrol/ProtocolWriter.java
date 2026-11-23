package com.hdz.wearcontrol;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 每个连接复用一条有界写队列；延时回查与调试入口也不能在界面线程写蓝牙。 */
public final class ProtocolWriter {
    private volatile ThreadPoolExecutor worker;

    public synchronized void start() {
        stop();
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(32), new ThreadFactory() {
                    @Override public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "headset-write");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
    }

    public boolean post(final Runnable task) {
        final ThreadPoolExecutor source = worker;
        if (source == null) return false;
        try {
            source.execute(new Runnable() {
                @Override public void run() {
                    if (worker == source && !Thread.currentThread().isInterrupted()) task.run();
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    public synchronized void stop() {
        ThreadPoolExecutor previous = worker;
        worker = null;
        if (previous != null) previous.shutdownNow();
    }
}

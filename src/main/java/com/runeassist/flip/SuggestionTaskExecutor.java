package com.runeassist.flip;

import java.util.List;
import java.util.concurrent.*;

public final class SuggestionTaskExecutor extends AbstractExecutorService {
    private ExecutorService delegate;
    private final Semaphore executionPermit = new Semaphore(1, true);

    public SuggestionTaskExecutor() { start(); }

    public synchronized void start() {
        if (delegate != null && !delegate.isShutdown()) return;
        delegate = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "RuneAssist-suggestion");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public synchronized void execute(Runnable command) {
        delegate.execute(() -> {
            executionPermit.acquireUninterruptibly();
            try { command.run(); }
            finally { executionPermit.release(); }
        });
    }
    @Override public synchronized void shutdown() { delegate.shutdown(); }
    @Override public synchronized List<Runnable> shutdownNow() { delegate.shutdown(); return List.of(); }
    @Override public synchronized boolean isShutdown() { return delegate.isShutdown(); }
    @Override public synchronized boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ExecutorService current;
        synchronized (this) { current = delegate; }
        return current.awaitTermination(timeout, unit);
    }
}

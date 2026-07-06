package com.study.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class CancelToken {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = Thread.ofVirtual().name("cancel-token-timer").unstarted(r);
        thread.setDaemon(true);
        return thread;
    });

    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        for (Runnable callback : callbacks) {
            callback.run();
        }
    }

    public void onCancel(Runnable callback) {
        if (isCancelled()) {
            callback.run();
            return;
        }
        callbacks.add(callback);
    }

    public CancelToken withTimeout(Duration timeout) {
        CancelToken child = new CancelToken();
        onCancel(child::cancel);
        TIMER.schedule(child::cancel, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return child;
    }
}

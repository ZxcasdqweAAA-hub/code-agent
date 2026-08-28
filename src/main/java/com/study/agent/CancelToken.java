package com.study.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancelToken {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = Thread.ofVirtual().name("cancel-token-timer").unstarted(r);
        thread.setDaemon(true);
        return thread;
    });

    private final Object lock = new Object();
    private final List<Registration> callbacks = new ArrayList<>();
    private volatile boolean cancelled;

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        List<Registration> pending;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pending = List.copyOf(callbacks);
            callbacks.clear();
        }
        for (Registration registration : pending) {
            registration.runOnce();
        }
    }

    public AutoCloseable onCancel(Runnable callback) {
        if (callback == null) {
            throw new NullPointerException("callback");
        }
        Registration registration = new Registration(callback);
        boolean runNow;
        synchronized (lock) {
            runNow = cancelled;
            if (!runNow) {
                callbacks.add(registration);
            }
        }
        if (runNow) {
            registration.runOnce();
        }
        return () -> {
            if (!registration.close()) {
                return;
            }
            synchronized (lock) {
                callbacks.remove(registration);
            }
        };
    }

    public CancelToken withTimeout(Duration timeout) {
        CancelToken child = new CancelToken();
        AutoCloseable parentRegistration = onCancel(child::cancel);
        var timeoutFuture = TIMER.schedule(() -> {
            child.cancel();
            closeQuietly(parentRegistration);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        child.onCancel(() -> timeoutFuture.cancel(false));
        return child;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static final class Registration {
        private final Runnable callback;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(Runnable callback) {
            this.callback = callback;
        }

        private boolean close() {
            return active.compareAndSet(true, false);
        }

        private void runOnce() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            try {
                callback.run();
            } catch (Throwable ignored) {
                // One broken listener must not prevent cancellation of other resources.
            }
        }
    }
}

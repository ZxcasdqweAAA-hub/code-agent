package com.study.task;

import com.study.agent.Agent;
import com.study.agent.CancelToken;
import com.study.conversation.ConversationManager;
import com.study.agent.ApprovalRequest;
import com.study.permission.Outcome;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class Manager implements AutoCloseable {
    private final Object lock = new Object();
    private final Map<String, BackgroundTask> tasks = new LinkedHashMap<>();
    private final Map<String, String> byName = new LinkedHashMap<>();
    private final ExecutorService doneExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final SubmissionPublisher<String> donePublisher = new SubmissionPublisher<>(doneExecutor, 32);
    private final SubmissionPublisher<String> approvalPublisher = new SubmissionPublisher<>(doneExecutor, 32);
    private final AtomicLong counter = new AtomicLong();
    private final AtomicLong approvalCounter = new AtomicLong();

    public String launch(Agent agent, ConversationManager conversation, String name, String taskText) {
        String id = nextId();
        return launchWithId(id, agent, conversation, name, taskText);
    }

    public String launchWithId(String id, Agent agent, ConversationManager conversation, String name, String taskText) {
        return launchWithId(id, agent, conversation, name, taskText, null);
    }

    public String launchWithId(String id, Agent agent, ConversationManager conversation, String name,
                               String taskText, Runnable cleanup) {
        CancelToken cancel = new CancelToken();
        BackgroundTask task = new BackgroundTask(id, name, agent, conversation, taskText, cancel, cleanup);
        synchronized (lock) {
            tasks.put(id, task);
            if (name != null && !name.isBlank()) {
                byName.put(name, id);
            }
        }
        Thread.ofVirtual().name("subagent-" + id).start(() -> runTask(task, cancel));
        return id;
    }

    public boolean resume(String id, String taskText) {
        BackgroundTask existing;
        synchronized (lock) {
            existing = tasks.get(id);
        }
        if (existing == null || existing.status() == Status.RUNNING || existing.status() == Status.WAITING_APPROVAL) {
            return false;
        }
        launchWithId(id, existing.agent(), existing.conversation(), existing.name(), taskText, existing.cleanup());
        return true;
    }

    public Optional<BackgroundTask> get(String id) {
        synchronized (lock) {
            return Optional.ofNullable(tasks.get(id));
        }
    }

    public List<BackgroundTask> list() {
        synchronized (lock) {
            return tasks.values().stream()
                    .sorted(Comparator.comparing(BackgroundTask::startTime).reversed())
                    .toList();
        }
    }

    public boolean stop(String id) {
        BackgroundTask task;
        synchronized (lock) {
            task = tasks.get(id);
        }
        if (task == null) {
            return false;
        }
        task.cancel();
        return true;
    }

    public Outcome awaitApproval(String taskId, ApprovalRequest request, CancelToken cancel) {
        BackgroundTask task = get(taskId).orElse(null);
        if (task == null) {
            return Outcome.DENY_ONCE;
        }
        String approvalId = "approval_" + Long.toUnsignedString(System.nanoTime() ^ approvalCounter.incrementAndGet(), 16);
        task.waitForApproval(new PendingApproval(approvalId, request));
        try {
            approvalPublisher.offer(taskId, 0, TimeUnit.MILLISECONDS, (sub, item) -> false);
        } catch (IllegalStateException ignored) {
        }
        try {
            while (!cancel.isCancelled()) {
                Outcome outcome = request.respond().poll(100, TimeUnit.MILLISECONDS);
                if (outcome != null) {
                    return outcome;
                }
            }
            return Outcome.DENY_ONCE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.DENY_ONCE;
        } finally {
            task.clearApproval(approvalId);
        }
    }

    public boolean respondApproval(String taskId, String approvalId, Outcome outcome) {
        BackgroundTask task = get(taskId).orElse(null);
        return task != null && task.respondApproval(approvalId, outcome == null ? Outcome.DENY_ONCE : outcome);
    }

    public Flow.Publisher<String> subscribeDone() {
        return donePublisher;
    }

    public Flow.Publisher<String> subscribeApprovals() {
        return approvalPublisher;
    }

    private void runTask(BackgroundTask task, CancelToken cancel) {
        try {
            String result = task.agent().runToCompletion(task.conversation(), task.task(), cancel);
            if (cancel.isCancelled()) {
                task.cancelled();
            } else {
                task.complete(result);
            }
        } catch (Throwable t) {
            if (cancel.isCancelled()) {
                task.cancelled();
            } else {
                task.fail(t);
            }
        } finally {
            if (task.cleanup() != null) {
                try {
                    task.cleanup().run();
                } catch (Throwable cleanupError) {
                    System.err.println("subagent worktree cleanup warning: " + cleanupError.getMessage());
                }
            }
            try {
                if (donePublisher.offer(task.id(), 0, TimeUnit.MILLISECONDS, (sub, item) -> false) < 0) {
                    System.err.println("task manager: done publisher full, dropping notification for " + task.id());
                }
            } catch (IllegalStateException ignored) {
                // Manager is closing; completion notifications are no longer needed.
            }
        }
    }

    private String nextId() {
        return nextTaskId();
    }

    public String nextTaskId() {
        long value = System.nanoTime() ^ counter.incrementAndGet();
        return "task_" + Long.toUnsignedString(value, 16);
    }

    @Override
    public void close() {
        donePublisher.close();
        approvalPublisher.close();
        doneExecutor.shutdownNow();
    }
}

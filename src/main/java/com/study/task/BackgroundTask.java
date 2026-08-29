package com.study.task;

import com.study.agent.Agent;
import com.study.agent.CancelToken;
import com.study.conversation.ConversationManager;

import java.time.Instant;
import com.study.permission.Outcome;

public final class BackgroundTask {
    private final String id;
    private final String name;
    private final Agent agent;
    private final ConversationManager conversation;
    private final String task;
    private final Instant startTime;
    private final CancelToken cancelToken;
    private volatile Status status;
    private volatile String result = "";
    private volatile String error = "";
    private volatile Instant endTime;
    private volatile PendingApproval pendingApproval;
    private final Runnable cleanup;

    BackgroundTask(String id, String name, Agent agent, ConversationManager conversation, String task, CancelToken cancelToken) {
        this(id, name, agent, conversation, task, cancelToken, null);
    }

    BackgroundTask(String id, String name, Agent agent, ConversationManager conversation, String task,
                   CancelToken cancelToken, Runnable cleanup) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.agent = agent;
        this.conversation = conversation;
        this.task = task == null ? "" : task;
        this.cancelToken = cancelToken;
        this.cleanup = cleanup;
        this.startTime = Instant.now();
        this.status = Status.RUNNING;
    }

    Runnable cleanup() {
        return cleanup;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Agent agent() {
        return agent;
    }

    public ConversationManager conversation() {
        return conversation;
    }

    public String task() {
        return task;
    }

    public Instant startTime() {
        return startTime;
    }

    public Instant endTime() {
        return endTime;
    }

    public Status status() {
        return status;
    }

    public String result() {
        return result;
    }

    public String error() {
        return error;
    }

    public PendingApproval pendingApproval() {
        return pendingApproval;
    }

    public void cancel() {
        cancelToken.cancel();
        PendingApproval pending = pendingApproval;
        if (pending != null) {
            pending.request().respond().offer(Outcome.DENY_ONCE);
        }
    }

    synchronized void waitForApproval(PendingApproval pending) {
        if (status == Status.RUNNING) {
            pendingApproval = pending;
            status = Status.WAITING_APPROVAL;
        }
    }

    synchronized boolean respondApproval(String approvalId, Outcome outcome) {
        PendingApproval pending = pendingApproval;
        if (pending == null || !pending.id().equals(approvalId)) {
            return false;
        }
        boolean offered = pending.request().respond().offer(outcome);
        if (offered) {
            pendingApproval = null;
            status = Status.RUNNING;
        }
        return offered;
    }

    synchronized void clearApproval(String approvalId) {
        if (pendingApproval != null && pendingApproval.id().equals(approvalId)) {
            pendingApproval = null;
            if (status == Status.WAITING_APPROVAL) {
                status = Status.RUNNING;
            }
        }
    }

    void complete(String result) {
        this.pendingApproval = null;
        this.result = result == null ? "" : result;
        this.status = Status.COMPLETED;
        this.endTime = Instant.now();
    }

    void fail(Throwable throwable) {
        this.pendingApproval = null;
        this.error = "Subagent execution failed";
        this.status = Status.FAILED;
        this.endTime = Instant.now();
    }

    void cancelled() {
        this.pendingApproval = null;
        this.status = Status.CANCELLED;
        this.endTime = Instant.now();
    }
}

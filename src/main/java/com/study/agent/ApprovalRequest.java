package com.study.agent;

import com.study.permission.Outcome;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public record ApprovalRequest(String name, String arguments, String reason, BlockingQueue<Outcome> respond) {
    public ApprovalRequest(String name, String arguments, String reason) {
        this(name, arguments, reason, new ArrayBlockingQueue<>(1));
    }
}

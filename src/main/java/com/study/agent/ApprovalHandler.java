package com.study.agent;

import com.study.permission.Outcome;

@FunctionalInterface
public interface ApprovalHandler {
    Outcome request(ApprovalRequest request, CancelToken cancel);
}

package com.study.worktree;

import java.io.IOException;

public class WorktreeHasChangesException extends IOException {
    public WorktreeHasChangesException(String name) {
        super("Worktree 有未提交修改或新增 commit，拒绝删除: " + name);
    }
}

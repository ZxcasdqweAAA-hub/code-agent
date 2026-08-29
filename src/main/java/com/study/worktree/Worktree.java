package com.study.worktree;

import java.nio.file.Path;
import java.time.Instant;

public record Worktree(
        String name,
        Path path,
        String branch,
        String basedOn,
        String headCommit,
        Instant created,
        boolean manual
) {
}

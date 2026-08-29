package com.study.worktree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class GitHelper {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private GitHelper() {
    }

    public static GitResult git(Path cwd, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "");
        Process process = builder.start();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Closing stdin prevents git from waiting for interactive input.
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = process.getInputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // Reported through the process exit/result.
            }
        });
        try {
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("git 命令超时: " + String.join(" ", command));
            }
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("git 命令被中断", e);
        }
        String text = out.toString(StandardCharsets.UTF_8);
        return new GitResult(process.exitValue(), text.stripTrailing());
    }

    public static String requireGit(Path cwd, String... args) throws IOException {
        GitResult result = git(cwd, args);
        if (result.exitCode() != 0) {
            throw new IOException("git " + String.join(" ", args) + " 失败: " + result.output());
        }
        return result.output();
    }

    public static String repoTop(Path cwd) throws IOException {
        return requireGit(cwd, "rev-parse", "--show-toplevel").trim();
    }

    public static String currentBranch(Path cwd) {
        try {
            GitResult result = git(cwd, "branch", "--show-current");
            if (result.exitCode() == 0 && !result.output().isBlank()) {
                return result.output().trim();
            }
        } catch (IOException ignored) {
            // Best-effort metadata.
        }
        return "";
    }

    public static String headSha(Path cwd) throws IOException {
        return requireGit(cwd, "rev-parse", "HEAD").trim();
    }

    public static boolean hasWorktreeChanges(Path wtPath, String baseCommit) {
        try {
            GitResult status = git(wtPath, "status", "--porcelain");
            if (status.exitCode() != 0 || !status.output().isBlank()) {
                return true;
            }
            if (baseCommit == null || baseCommit.isBlank()) {
                return false;
            }
            GitResult commits = git(wtPath, "rev-list", "--count", baseCommit + "..HEAD");
            if (commits.exitCode() != 0) {
                return true;
            }
            return Integer.parseInt(commits.output().trim()) > 0;
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean hasUncommittedChanges(Path path) {
        try {
            GitResult status = git(path, "status", "--porcelain");
            return status.exitCode() != 0 || !status.output().isBlank();
        } catch (IOException e) {
            return true;
        }
    }

    public static boolean hasUnpushedCommits(Path wtPath) {
        try {
            GitResult result = git(wtPath, "rev-list", "--max-count=1", "HEAD", "--not", "--remotes");
            return result.exitCode() != 0 || !result.output().isBlank();
        } catch (IOException e) {
            return true;
        }
    }

    public static String resolveHeadShaFromFS(Path wtPath) {
        try {
            if (!Files.exists(wtPath.resolve(".git"))) {
                return "";
            }
            return headSha(wtPath);
        } catch (IOException e) {
            return "";
        }
    }

    public record GitResult(int exitCode, String output) {
    }
}

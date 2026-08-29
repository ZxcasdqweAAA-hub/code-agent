package com.study.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class WorktreeManager {
    private static final List<String> DEFAULT_SYMLINK_DIRS = List.of("node_modules", ".venv", "vendor");

    private final Path repoRoot;
    private final Path worktreeDir;
    private final Path sessionFile;
    private final SessionStore sessionStore;
    private final List<String> symlinkDirs;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Worktree> active = new HashMap<>();
    private WorktreeSession currentSession;

    public WorktreeManager(Path repoRoot) throws IOException {
        this(repoRoot, DEFAULT_SYMLINK_DIRS);
    }

    public WorktreeManager(Path repoRoot, List<String> symlinkDirs) throws IOException {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        String top = GitHelper.repoTop(this.repoRoot);
        Path topPath = Path.of(top).toAbsolutePath().normalize();
        if (!samePath(topPath, this.repoRoot)) {
            throw new IOException("当前目录不是 git 仓库根目录: " + this.repoRoot + "，仓库根目录是 " + topPath);
        }
        this.worktreeDir = this.repoRoot.resolve(".code-agent").resolve("worktrees");
        this.sessionFile = this.repoRoot.resolve(".code-agent").resolve("worktree_session.json");
        this.sessionStore = new SessionStore(sessionFile);
        this.symlinkDirs = symlinkDirs == null ? DEFAULT_SYMLINK_DIRS : List.copyOf(symlinkDirs);
        Files.createDirectories(worktreeDir);
        loadSession();
        scanActive();
        warnGitignore();
    }

    public Worktree create(String name, String baseRef, boolean manual) throws IOException {
        WorktreeSlug.validate(name);
        String base = baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef;
        String flat = WorktreeSlug.flatten(name);
        Path wtPath = worktreeDir.resolve(flat).toAbsolutePath().normalize();
        String branch = "worktree-" + flat;
        lock.lock();
        try {
            if (active.containsKey(name)) {
                throw new IOException("Worktree 已存在: " + name);
            }
            if (Files.exists(wtPath)) {
                Worktree restored = restoreExisting(name, wtPath, branch, base, manual);
                active.put(name, restored);
                return restored;
            }
        } finally {
            lock.unlock();
        }

        try {
            GitHelper.requireGit(repoRoot, "worktree", "add", "-B", branch, wtPath.toString(), base);
            PostCreationSetup.run(repoRoot, wtPath, symlinkDirs);
            Worktree wt = new Worktree(name, wtPath, branch, base, GitHelper.headSha(wtPath), Instant.now(), manual);
            lock.lock();
            try {
                active.put(name, wt);
            } finally {
                lock.unlock();
            }
            return wt;
        } catch (IOException e) {
            cleanupCreateFailure(wtPath);
            throw e;
        }
    }

    public WorktreeSession enter(String name) throws IOException {
        lock.lock();
        try {
            Worktree wt = requireActive(name);
            WorktreeSession session = new WorktreeSession(
                    repoRoot.toString(),
                    wt.path().toString(),
                    wt.name(),
                    GitHelper.currentBranch(repoRoot),
                    safeHead(repoRoot),
                    UUID.randomUUID().toString(),
                    false);
            currentSession = session;
            sessionStore.write(session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    public ExitReport exit(String name, ExitAction action, ExitOptions opts) throws IOException {
        lock.lock();
        try {
            if (currentSession == null || !currentSession.worktreeName().equals(name)) {
                throw new IOException("只能退出当前 active worktree: " + name);
            }
        } finally {
            lock.unlock();
        }
        ExitReport report = action == ExitAction.REMOVE
                ? removeInternal(name, options(opts), true)
                : keepExit(name);
        lock.lock();
        try {
            currentSession = null;
            sessionStore.write(null);
        } finally {
            lock.unlock();
        }
        return report;
    }

    public void remove(String name, ExitOptions opts) throws IOException {
        removeInternal(name, options(opts), false);
    }

    public AutoCleanupReport autoCleanup(String name) throws IOException {
        Worktree wt;
        lock.lock();
        try {
            wt = active.get(name);
        } finally {
            lock.unlock();
        }
        if (wt == null) {
            return new AutoCleanupReport(false, "", "");
        }
        if (wt.manual()) {
            return new AutoCleanupReport(true, wt.path().toString(), wt.branch());
        }
        if (!GitHelper.hasWorktreeChanges(wt.path(), wt.headCommit())) {
            remove(name, new ExitOptions(true));
            return new AutoCleanupReport(false, wt.path().toString(), wt.branch());
        }
        return new AutoCleanupReport(true, wt.path().toString(), wt.branch());
    }

    public List<String> sweepStale(Instant cutoff) {
        List<String> removed = new ArrayList<>();
        try {
            if (!Files.isDirectory(worktreeDir)) {
                return removed;
            }
            try (var stream = Files.list(worktreeDir)) {
                for (Path path : stream.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList()) {
                    String flat = path.getFileName().toString();
                    String name = flat.replace("+", "/");
                    if (!WorktreeNaming.isEphemeral(flat)) {
                        continue;
                    }
                    if (Files.getLastModifiedTime(path).toInstant().isAfter(cutoff)) {
                        continue;
                    }
                    WorktreeSession session = currentSession;
                    if (session != null && Path.of(session.worktreePath()).toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())) {
                        continue;
                    }
                    Worktree wt = active.getOrDefault(name, restoreExisting(name, path, "worktree-" + flat, "HEAD", false));
                    if (GitHelper.hasWorktreeChanges(path, wt.headCommit()) || GitHelper.hasUnpushedCommits(path)) {
                        continue;
                    }
                    remove(name, new ExitOptions(true));
                    removed.add(name);
                }
            }
        } catch (Exception e) {
            System.err.println("Worktree 过期清理警告: " + e.getMessage());
        }
        return removed;
    }

    public List<Worktree> list() {
        lock.lock();
        try {
            return active.values().stream()
                    .sorted(Comparator.comparing(Worktree::name))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public Optional<Worktree> get(String name) {
        lock.lock();
        try {
            return Optional.ofNullable(active.get(name));
        } finally {
            lock.unlock();
        }
    }

    public WorktreeSession currentSession() {
        lock.lock();
        try {
            return currentSession;
        } finally {
            lock.unlock();
        }
    }

    public Path repoRoot() {
        return repoRoot;
    }

    private ExitReport keepExit(String name) throws IOException {
        Worktree wt;
        lock.lock();
        try {
            wt = requireActive(name);
        } finally {
            lock.unlock();
        }
        return new ExitReport(false, wt.path().toString(), wt.branch());
    }

    private ExitReport removeInternal(String name, ExitOptions opts, boolean requireCurrent) throws IOException {
        Worktree wt;
        lock.lock();
        try {
            wt = requireActive(name);
            if (requireCurrent && (currentSession == null || !currentSession.worktreeName().equals(name))) {
                throw new IOException("只能删除当前 active worktree: " + name);
            }
        } finally {
            lock.unlock();
        }
        if (!opts.discardChanges() && GitHelper.hasWorktreeChanges(wt.path(), wt.headCommit())) {
            throw new WorktreeHasChangesException(name);
        }
        GitHelper.requireGit(repoRoot, "worktree", "remove", "--force", wt.path().toString());
        sleepBriefly();
        GitHelper.git(repoRoot, "branch", "-D", wt.branch());
        lock.lock();
        try {
            active.remove(name);
            if (currentSession != null && currentSession.worktreeName().equals(name)) {
                currentSession = null;
                sessionStore.write(null);
            }
        } finally {
            lock.unlock();
        }
        return new ExitReport(true, wt.path().toString(), wt.branch());
    }

    private Worktree requireActive(String name) throws IOException {
        Worktree wt = active.get(name);
        if (wt == null) {
            throw new IOException("Worktree 不存在: " + name);
        }
        return wt;
    }

    private ExitOptions options(ExitOptions opts) {
        return opts == null ? new ExitOptions(false) : opts;
    }

    private void loadSession() throws IOException {
        try {
            currentSession = sessionStore.read();
            if (currentSession != null && !Files.isDirectory(Path.of(currentSession.worktreePath()))) {
                currentSession = null;
                sessionStore.write(null);
                System.err.println("session worktree gone, cleared");
            }
        } catch (Exception e) {
            currentSession = null;
            sessionStore.write(null);
            System.err.println("Worktree session 文件损坏，已清空: " + e.getMessage());
        }
    }

    private void scanActive() throws IOException {
        if (!Files.isDirectory(worktreeDir)) {
            return;
        }
        try (var stream = Files.list(worktreeDir)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                String flat = path.getFileName().toString();
                String name = flat.replace("+", "/");
                active.put(name, restoreExisting(name, path, "worktree-" + flat, "HEAD", false));
            }
        }
    }

    private Worktree restoreExisting(String name, Path path, String branch, String basedOn, boolean manual) throws IOException {
        Instant created = Files.exists(path) ? Files.getLastModifiedTime(path).toInstant() : Instant.now();
        String head = GitHelper.resolveHeadShaFromFS(path);
        return new Worktree(name, path.toAbsolutePath().normalize(), branch, basedOn, head, created, manual);
    }

    private String safeHead(Path path) {
        try {
            return GitHelper.headSha(path);
        } catch (IOException e) {
            return "";
        }
    }

    private void cleanupCreateFailure(Path wtPath) {
        try {
            if (Files.exists(wtPath)) {
                GitHelper.git(repoRoot, "worktree", "remove", "--force", wtPath.toString());
            }
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private void warnGitignore() {
        Path gitignore = repoRoot.resolve(".gitignore");
        try {
            String text = Files.isRegularFile(gitignore) ? Files.readString(gitignore) : "";
            boolean ignoresAllCodeAgent = text.lines().map(String::trim).anyMatch(".code-agent/"::equals);
            if (!ignoresAllCodeAgent
                    && (!text.contains(".code-agent/worktrees/") || !text.contains(".code-agent/worktree_session.json"))) {
                System.err.println("Worktree 提示: 建议在 .gitignore 中忽略 .code-agent/worktrees/ 和 .code-agent/worktree_session.json");
            }
        } catch (IOException ignored) {
            // Non-blocking hint.
        }
    }

    private static boolean samePath(Path left, Path right) {
        try {
            return Files.isSameFile(left, right);
        } catch (IOException e) {
            return left.equals(right);
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

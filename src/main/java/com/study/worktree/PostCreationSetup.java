package com.study.worktree;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public final class PostCreationSetup {
    private PostCreationSetup() {
    }

    public static void run(Path repoRoot, Path wtPath, List<String> symlinkDirs) {
        copyLocalConfig(repoRoot, wtPath);
        configureHooks(repoRoot, wtPath);
        linkLargeDirs(repoRoot, wtPath, symlinkDirs == null ? List.of() : symlinkDirs);
        copyIncludedIgnoredFiles(repoRoot, wtPath);
    }

    private static void copyLocalConfig(Path repoRoot, Path wtPath) {
        for (String relative : List.of(".code-agent/config.yaml", ".code-agent/settings.local.yaml")) {
            try {
                Path src = repoRoot.resolve(relative);
                Path dst = wtPath.resolve(relative);
                if (Files.isRegularFile(src) && !Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (IOException e) {
                warn("复制配置失败", e);
            }
        }
    }

    private static void configureHooks(Path repoRoot, Path wtPath) {
        try {
            String hooks = GitHelper.git(repoRoot, "config", "--get", "core.hooksPath").output();
            Path husky = repoRoot.resolve(".husky");
            String hooksPath = !hooks.isBlank() ? repoRoot.resolve(hooks.trim()).toString()
                    : Files.isDirectory(husky) ? husky.toString()
                    : "";
            if (!hooksPath.isBlank()) {
                GitHelper.git(wtPath, "config", "core.hooksPath", hooksPath);
            }
        } catch (IOException e) {
            warn("配置 hooks 失败", e);
        }
    }

    private static void linkLargeDirs(Path repoRoot, Path wtPath, List<String> symlinkDirs) {
        for (String name : symlinkDirs) {
            try {
                Path src = repoRoot.resolve(name);
                Path dst = wtPath.resolve(name);
                if (Files.isDirectory(src) && !Files.exists(dst)) {
                    Files.createSymbolicLink(dst, src);
                }
            } catch (Exception e) {
                warn("创建软链失败: " + name, e);
            }
        }
    }

    private static void copyIncludedIgnoredFiles(Path repoRoot, Path wtPath) {
        Path include = repoRoot.resolve(".worktreeinclude");
        if (!Files.isRegularFile(include)) {
            return;
        }
        try {
            List<String> patterns = Files.readAllLines(include).stream()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
            if (patterns.isEmpty()) {
                return;
            }
            List<PathMatcher> matchers = patterns.stream()
                    .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                    .toList();
            GitHelper.GitResult ignored = GitHelper.git(repoRoot, "ls-files", "--others", "--ignored",
                    "--exclude-standard", "--directory");
            if (ignored.exitCode() != 0 || ignored.output().isBlank()) {
                return;
            }
            for (String line : ignored.output().split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                Path rel = Path.of(line.replace("/", java.io.File.separator));
                if (matchers.stream().noneMatch(matcher -> matcher.matches(rel))) {
                    continue;
                }
                Path source = repoRoot.resolve(rel).normalize();
                if (!source.startsWith(repoRoot)) {
                    continue;
                }
                if (Files.isRegularFile(source)) {
                    copyOne(repoRoot, wtPath, source);
                } else if (Files.isDirectory(source)) {
                    copyDirectory(repoRoot, wtPath, source);
                }
            }
        } catch (IOException e) {
            warn("复制 .worktreeinclude 文件失败", e);
        }
    }

    private static void copyDirectory(Path repoRoot, Path wtPath, Path sourceDir) throws IOException {
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                copyOne(repoRoot, wtPath, file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyOne(Path repoRoot, Path wtPath, Path source) throws IOException {
        Path rel = repoRoot.relativize(source);
        Path dst = wtPath.resolve(rel);
        if (!Files.exists(dst)) {
            Files.createDirectories(dst.getParent());
            Files.copy(source, dst, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void warn(String message, Exception e) {
        System.err.println("Worktree 创建后设置警告: " + message + ": " + e.getMessage());
    }
}

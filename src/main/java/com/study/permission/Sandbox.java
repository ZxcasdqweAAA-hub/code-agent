package com.study.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Sandbox {
    private final Path root;

    public Sandbox(Path root) {
        this.root = normalizeRoot(root);
    }

    public Path root() {
        return root;
    }

    public PermissionResult checkPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return PermissionResult.allow("no path argument");
        }
        try {
            Path resolved = resolve(rawPath);
            if (isInside(resolved)) {
                return PermissionResult.allow("path is inside workspace");
            }
            return PermissionResult.deny("Path escapes workspace: " + rawPath);
        } catch (Exception e) {
            return PermissionResult.deny("Path is not allowed: " + rawPath + " (" + e.getMessage() + ")");
        }
    }

    private Path resolve(String rawPath) throws IOException {
        Path path = Path.of(rawPath);
        Path absolute = path.isAbsolute() ? path : root.resolve(path);
        Path normalized = absolute.normalize();
        if (Files.exists(normalized)) {
            return normalized.toRealPath();
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            return normalized.toAbsolutePath().normalize();
        }
        Path realParent = Files.exists(parent) ? parent.toRealPath() : nearestExisting(parent);
        return realParent.resolve(normalized.getFileName()).normalize();
    }

    private Path nearestExisting(Path path) throws IOException {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        if (current == null) {
            return path.toAbsolutePath().normalize();
        }
        Path real = current.toRealPath();
        Path suffix = current.relativize(path);
        return real.resolve(suffix).normalize();
    }

    private boolean isInside(Path path) throws IOException {
        Path normalized = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
        return normalized.startsWith(root);
    }

    private static Path normalizeRoot(Path root) {
        Path base = root == null ? Path.of("") : root;
        try {
            return base.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            return base.toAbsolutePath().normalize();
        }
    }
}

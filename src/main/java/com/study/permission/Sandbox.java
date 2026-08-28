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

    public PathResult inspect(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return new PathResult(PathStatus.INSIDE, "path resolves to workspace root");
        }
        try {
            Path resolved = resolve(rawPath);
            if (isInside(resolved)) {
                return new PathResult(PathStatus.INSIDE, "path is inside workspace");
            }
            return new PathResult(PathStatus.OUTSIDE, "path is outside workspace: " + rawPath);
        } catch (Exception e) {
            return new PathResult(PathStatus.UNRESOLVED,
                    "path cannot be resolved: " + rawPath + " (" + e.getMessage() + ")");
        }
    }

    private Path resolve(String rawPath) throws IOException {
        Path path = Path.of(rawPath);
        Path absolute = path.isAbsolute() ? path : root.resolve(path);
        Path normalized = absolute.normalize();
        if (Files.exists(normalized)) {
            return normalized.toRealPath();
        }
        Path current = normalized;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("no existing path ancestor");
        }
        Path real = current.toRealPath();
        Path suffix = current.relativize(normalized);
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

    public enum PathStatus {
        INSIDE,
        OUTSIDE,
        UNRESOLVED
    }

    public record PathResult(PathStatus status, String reason) {
    }
}

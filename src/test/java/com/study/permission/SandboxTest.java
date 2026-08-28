package com.study.permission;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SandboxTest {
    @TempDir
    Path tempDir;

    @Test
    void classifiesInsideOutsideAndParentTraversal() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path inside = Files.writeString(workspace.resolve("inside.txt"), "ok");
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "no");
        Sandbox sandbox = new Sandbox(workspace);

        assertEquals(Sandbox.PathStatus.INSIDE, sandbox.inspect(inside.toString()).status());
        assertEquals(Sandbox.PathStatus.INSIDE, sandbox.inspect("inside.txt").status());
        assertEquals(Sandbox.PathStatus.OUTSIDE, sandbox.inspect(outside.toString()).status());
        assertEquals(Sandbox.PathStatus.OUTSIDE, sandbox.inspect("../outside.txt").status());
    }

    @Test
    void preservesAllMissingPathSegmentsWhenResolvingNearestAncestor() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Sandbox sandbox = new Sandbox(workspace);

        assertEquals(Sandbox.PathStatus.INSIDE,
                sandbox.inspect("a/b/c/new.txt").status());
        assertEquals(Sandbox.PathStatus.OUTSIDE,
                sandbox.inspect("../missing/a/b/new.txt").status());
    }

    @Test
    void detectsSymlinkEscapeWhenPlatformAllowsSymlinks() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = workspace.resolve("external");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + e.getMessage());
        }

        assertEquals(Sandbox.PathStatus.OUTSIDE,
                new Sandbox(workspace).inspect(link.resolve("file.txt").toString()).status());
    }

    @Test
    void reportsInvalidPathAsUnresolved() {
        Sandbox sandbox = new Sandbox(tempDir);

        assertEquals(Sandbox.PathStatus.UNRESOLVED, sandbox.inspect("bad\u0000path").status());
    }
}

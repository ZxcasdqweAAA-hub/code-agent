package com.study.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void definitionsReturnsSixOrdered() {
        ToolRegistry registry = ToolRegistry.createDefault();

        List<Map<String, Object>> schemas = registry.getAllSchemas("openai");

        assertEquals(6, schemas.size());
        assertEquals("ReadFile", schemas.get(0).get("name"));
        assertEquals("WriteFile", schemas.get(1).get("name"));
        assertTrue(registry.get("Grep").isPresent());
        assertTrue(registry.get("Missing").isEmpty());
    }

    @Test
    void readFileReturnsNumberedContentAndMissingErrors() throws Exception {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "alpha\nbeta");
        Tool read = new ReadFileTool();

        ToolExecutionResult ok = read.execute(Map.of("path", file.toString()));
        ToolExecutionResult missing = read.execute(Map.of("path", tempDir.resolve("missing.txt").toString()));

        assertFalse(ok.error());
        assertTrue(ok.content().contains("1\talpha"));
        assertTrue(missing.error());
    }

    @Test
    void writeFileCreatesParents() throws Exception {
        Path file = tempDir.resolve("a/b/c.txt");

        ToolExecutionResult result = new WriteFileTool().execute(Map.of("path", file.toString(), "content", "hello"));

        assertFalse(result.error());
        assertEquals("hello", Files.readString(file));
    }

    @Test
    void editFileRequiresUniqueMatch() throws Exception {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "one two two");
        EditFileTool tool = new EditFileTool();

        ToolExecutionResult zero = tool.execute(Map.of("path", file.toString(), "old_string", "missing", "new_string", "x"));
        ToolExecutionResult many = tool.execute(Map.of("path", file.toString(), "old_string", "two", "new_string", "x"));
        ToolExecutionResult one = tool.execute(Map.of("path", file.toString(), "old_string", "one", "new_string", "1"));

        assertTrue(zero.error());
        assertTrue(many.error());
        assertTrue(many.content().contains("2"));
        assertFalse(one.error());
        assertEquals("1 two two", Files.readString(file));
    }

    @Test
    void bashReturnsOutput() {
        ToolExecutionResult result = new BashTool().execute(Map.of("command", "echo hello"));

        assertFalse(result.error());
        assertTrue(result.content().contains("hello"));
        assertTrue(result.content().contains("exit_code: 0"));
    }

    @Test
    void globAndGrepFindFilesAndContent() throws Exception {
        Path rootPom = tempDir.resolve("pom.xml");
        Files.writeString(rootPom, "<project>needle</project>");
        Path file = tempDir.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main { String marker = \"needle\"; }");

        ToolExecutionResult glob = new GlobTool().execute(Map.of("path", tempDir.toString(), "pattern", "**/*.java"));
        ToolExecutionResult grep = new GrepTool().execute(Map.of("path", tempDir.toString(), "pattern", "needle", "glob", "**/*.java"));
        ToolExecutionResult rootGlob = new GlobTool().execute(Map.of("path", tempDir.toString(), "pattern", "**/pom.xml"));

        assertFalse(glob.error());
        assertTrue(glob.content().contains("Main.java"));
        assertFalse(grep.error());
        assertTrue(grep.content().contains("needle"));
        assertFalse(rootGlob.error());
        assertTrue(rootGlob.content().contains("pom.xml"));
    }
}

package com.study.skills;

import com.study.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void projectSkillOverridesBuiltin() throws Exception {
        Path skillDir = tempDir.resolve(".code-agent/skills/commit");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: commit
                description: Project commit skill
                ---

                Body
                """);

        Catalog catalog = Catalog.load(tempDir, Set.of("help"));

        assertEquals("Project commit skill", catalog.get("commit").orElseThrow().meta().description());
    }

    @Test
    void validatesMissingAllowedTool() throws Exception {
        Path skillDir = tempDir.resolve(".code-agent/skills/demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: demo
                description: Demo
                allowed_tools: [MissingTool]
                ---

                Body
                """);

        Catalog catalog = Catalog.load(tempDir, Set.of());

        assertEquals(1, catalog.validateTools(ToolRegistry.createDefault()).size());
    }

    @Test
    void loadsBuiltinSkills() {
        Catalog catalog = Catalog.load(tempDir, Set.of());

        assertTrue(catalog.names().contains("commit"));
        assertTrue(catalog.names().contains("review"));
        assertTrue(catalog.names().contains("test"));
    }

    @Test
    void reviewBuiltinIsStrictlyReadOnly() {
        Catalog catalog = Catalog.load(tempDir, Set.of());
        Skill review = catalog.get("review").orElseThrow();

        assertEquals(java.util.List.of("ReadFile", "Grep", "Glob"), review.meta().allowedTools());
        assertEquals("fork", review.meta().mode());
        assertEquals("recent", review.meta().forkContext());
        assertFalse(review.meta().allowedTools().contains("Bash"));
        assertFalse(review.meta().allowedTools().contains("WriteFile"));
        assertFalse(review.meta().allowedTools().contains("EditFile"));
    }
}

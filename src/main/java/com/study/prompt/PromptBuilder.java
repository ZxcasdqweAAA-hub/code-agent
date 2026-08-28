package com.study.prompt;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class PromptBuilder {
    private PromptBuilder() {
    }

    public static String assembleSystem(List<Module> modules) {
        return modules.stream()
                .filter(module -> module.content() != null && !module.content().isBlank())
                .sorted(Comparator.comparingInt(Module::priority))
                .map(module -> module.content().strip())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    public static String buildSystemPrompt() {
        return buildSystemPrompt("", "");
    }

    public static String buildSystemPrompt(String instructions) {
        return buildSystemPrompt(instructions, "");
    }

    public static String buildSystemPrompt(String instructions, String memory) {
        return buildSystemPrompt(instructions, memory, "");
    }

    public static String buildSystemPrompt(String instructions, String memory, String skillsCatalog) {
        return assembleSystem(Stream.concat(
                Modules.fixedModules().stream(),
                Modules.optionalModules(instructions, memory, skillsCatalog).stream()).toList());
    }
}

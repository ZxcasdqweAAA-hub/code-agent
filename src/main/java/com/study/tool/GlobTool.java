package com.study.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class GlobTool implements Tool {
    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return "按文件名或 glob 模式查找文件路径。需要找某类文件、查文件是否存在、列出目录文件时使用。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", ToolSupport.stringProperty("glob 模式，如 **/*.java"));
        props.put("path", ToolSupport.stringProperty("搜索起点目录，默认当前目录"));
        return ToolSupport.objectSchema(props, "pattern");
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        try {
            String pattern = ToolSupport.requireString(args, "pattern");
            Path root = Path.of(ToolSupport.optionalString(args, "path", "."));
            var matches = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> GlobMatcher.matches(pattern, root.relativize(path)))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(100)
                    .map(Path::toString)
                    .collect(Collectors.toList());
            if (matches.isEmpty()) {
                return ToolExecutionResult.ok("无匹配");
            }
            return ToolExecutionResult.ok(String.join(System.lineSeparator(), matches));
        } catch (Exception e) {
            return ToolExecutionResult.error("查找文件失败: " + e.getMessage());
        }
    }
}

package com.study.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReadFileTool implements Tool {
    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return "读取文本文件内容，并以带行号的形式返回。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", ToolSupport.stringProperty("要读取的文件路径"));
        return ToolSupport.objectSchema(props, "path");
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        return execute(ToolContext.root(), args);
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, Map<String, Object> args) {
        try {
            Path path = context.resolvePath(ToolSupport.requireString(args, "path"));
            if (!Files.exists(path)) {
                return ToolExecutionResult.error("文件不存在: " + path);
            }
            if (Files.isDirectory(path)) {
                return ToolExecutionResult.error("路径是目录，不是文件: " + path);
            }
            String content = Files.readString(path);
            String[] lines = content.split("\\R", -1);
            StringBuilder numbered = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                numbered.append(String.format("%6d\t%s", i + 1, lines[i]));
                if (i < lines.length - 1) {
                    numbered.append(System.lineSeparator());
                }
            }
            return ToolExecutionResult.ok(Truncate.byLinesAndBytes(numbered.toString(), 2000, 256 * 1024));
        } catch (Exception e) {
            return ToolExecutionResult.error("读取文件失败: " + e.getMessage());
        }
    }
}

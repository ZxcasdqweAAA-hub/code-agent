package com.study.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class EditFileTool implements Tool {
    @Override
    public String name() {
        return "EditFile";
    }

    @Override
    public String description() {
        return "把文件中的唯一 old_string 替换为 new_string。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", ToolSupport.stringProperty("要修改的文件路径"));
        props.put("old_string", ToolSupport.stringProperty("文件中必须唯一匹配的原文片段"));
        props.put("new_string", ToolSupport.stringProperty("替换后的新文本"));
        return ToolSupport.objectSchema(props, "path", "old_string", "new_string");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        try {
            Path path = Path.of(ToolSupport.requireString(args, "path"));
            String oldString = ToolSupport.requireString(args, "old_string");
            String newString = ToolSupport.requireString(args, "new_string");
            String content = Files.readString(path);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return ToolExecutionResult.error("未找到匹配的内容");
            }
            if (count > 1) {
                return ToolExecutionResult.error("匹配到 " + count + " 处，old_string 不唯一，请提供更长上下文");
            }
            Files.writeString(path, content.replace(oldString, newString));
            return ToolExecutionResult.ok("已修改 " + path);
        } catch (Exception e) {
            return ToolExecutionResult.error("修改文件失败: " + e.getMessage());
        }
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(target, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + target.length();
        }
    }
}

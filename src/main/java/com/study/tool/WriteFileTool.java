package com.study.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class WriteFileTool implements Tool {
    @Override
    public String name() {
        return "WriteFile";
    }

    @Override
    public String description() {
        return "创建或覆盖文本文件，父目录不存在时自动创建。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", ToolSupport.stringProperty("要写入的文件路径"));
        props.put("content", ToolSupport.stringProperty("要写入的完整文本内容"));
        return ToolSupport.objectSchema(props, "path", "content");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        try {
            Path path = Path.of(ToolSupport.requireString(args, "path"));
            String content = ToolSupport.requireString(args, "content");
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content);
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            return ToolExecutionResult.ok("已写入 " + path + " (" + bytes + " 字节)");
        } catch (Exception e) {
            return ToolExecutionResult.error("写入文件失败: " + e.getMessage());
        }
    }
}

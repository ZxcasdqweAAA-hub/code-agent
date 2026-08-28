package com.study.tool;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class GrepTool implements Tool {
    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return "在项目文件内容中搜索关键词或 Java 正则，返回 file:line:content。需要查代码里哪里出现某内容时使用。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", ToolSupport.stringProperty("Java Pattern 正则表达式"));
        props.put("path", ToolSupport.stringProperty("搜索起点目录，默认当前目录"));
        props.put("glob", ToolSupport.stringProperty("可选文件名 glob 过滤，如 **/*.java"));
        return ToolSupport.objectSchema(props, "pattern");
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
            Pattern regex = Pattern.compile(ToolSupport.requireString(args, "pattern"));
            Path root = context.resolvePath(ToolSupport.optionalString(args, "path", "."));
            String glob = ToolSupport.optionalString(args, "glob", "");
            List<String> hits = new ArrayList<>();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    Path rel = root.relativize(path);
                    if (!glob.isBlank() && !GlobMatcher.matches(glob, rel)) {
                        continue;
                    }
                    scanFile(regex, path, hits);
                    if (hits.size() >= 100) {
                        hits.add("[truncated]");
                        break;
                    }
                }
            }
            if (hits.isEmpty()) {
                return ToolExecutionResult.ok("无命中");
            }
            return ToolExecutionResult.ok(String.join(System.lineSeparator(), hits));
        } catch (Exception e) {
            return ToolExecutionResult.error("搜索失败: " + e.getMessage());
        }
    }

    private void scanFile(Pattern regex, Path path, List<String> hits) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (regex.matcher(line).find()) {
                    hits.add(path + ":" + lineNo + ":" + Truncate.chars(line, 500));
                    if (hits.size() >= 100) {
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
            // Binary or unreadable files are skipped; other files can still provide useful matches.
        }
    }
}

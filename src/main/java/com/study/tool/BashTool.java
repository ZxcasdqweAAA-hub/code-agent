package com.study.tool;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {
    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return "在当前工作目录执行 shell 命令，返回输出和退出码。读文件、找文件、搜内容请优先用 ReadFile、Glob、Grep，不要用 Bash 拼凑。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", ToolSupport.stringProperty("要执行的 shell 命令"));
        return ToolSupport.objectSchema(props, "command");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args) {
        Process process = null;
        try {
            String command = ToolSupport.requireString(args, "command");
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder builder = windows
                    ? new ProcessBuilder("cmd", "/C", command)
                    : new ProcessBuilder("sh", "-c", command);
            builder.redirectErrorStream(true);
            process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process running = process;
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var in = running.getInputStream()) {
                    in.transferTo(output);
                } catch (Exception ignored) {
                    // Output read failures are reported through the process result.
                }
            });
            boolean finished = process.waitFor(ToolRegistry.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolExecutionResult.error("命令超时: " + command);
            }
            reader.join(1000);
            String text = output.toString(StandardCharsets.UTF_8);
            String result = "exit_code: " + process.exitValue() + System.lineSeparator()
                    + "output:" + System.lineSeparator()
                    + Truncate.chars(text, 30_000);
            return new ToolExecutionResult(result, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return ToolExecutionResult.error("命令已中断");
        } catch (Exception e) {
            return ToolExecutionResult.error("执行命令失败: " + e.getMessage());
        }
    }
}

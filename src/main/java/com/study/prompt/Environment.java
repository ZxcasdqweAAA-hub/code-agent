package com.study.prompt;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public record Environment(String workingDir, String platform, String date, String gitStatus, String version, String model) {
    public static Environment gather(String version, String model) {
        return new Environment(
                safeValue(java.lang.System.getProperty("user.dir")),
                safeValue(java.lang.System.getProperty("os.name")),
                LocalDate.now().toString(),
                collectGitStatus(),
                safeValue(version),
                safeValue(model));
    }

    public String render() {
        List<String> lines = new ArrayList<>();
        add(lines, "Working directory", workingDir);
        add(lines, "Platform", platform);
        add(lines, "Date", date);
        add(lines, "Git status", gitStatus);
        add(lines, "Version", version);
        add(lines, "Model", model);
        if (lines.isEmpty()) {
            return "";
        }
        return "Environment" + java.lang.System.lineSeparator()
                + String.join(java.lang.System.lineSeparator(), lines);
    }

    private static void add(List<String> lines, String key, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(key + ": " + value);
        }
    }

    private static String safeValue(String text) {
        return text == null ? "" : text;
    }

    private static String collectGitStatus() {
        Process process = null;
        try {
            process = new ProcessBuilder("git", "status", "--porcelain")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            String output = new String(process.getInputStream().readAllBytes());
            if (output.isBlank()) {
                return "clean";
            }
            long count = output.lines().filter(line -> !line.isBlank()).count();
            return count + " changed file" + (count == 1 ? "" : "s");
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}

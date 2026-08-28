package com.study.permission;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SettingsLoader {
    private SettingsLoader() {
    }

    public static LoadedSettings load(Path path) {
        if (path == null || !Files.exists(path)) {
            return LoadedSettings.empty();
        }
        List<String> warnings = new ArrayList<>();
        Object parsed;
        try (Reader reader = Files.newBufferedReader(path)) {
            parsed = new Yaml().load(reader);
        } catch (Exception e) {
            warnings.add(warning(path, "YAML 无法解析，已忽略该文件"));
            return new LoadedSettings(Settings.empty(), new LinkedHashMap<>(), warnings);
        }
        if (parsed == null) {
            return LoadedSettings.empty();
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            warnings.add(warning(path, "配置根节点必须是对象，已忽略该文件"));
            return new LoadedSettings(Settings.empty(), new LinkedHashMap<>(), warnings);
        }
        LinkedHashMap<String, Object> document = stringKeyMap(root);
        String defaultMode = parseMode(document.get("defaultMode"), path, warnings);
        List<String> allow = List.of();
        List<String> deny = List.of();
        Object permissionsValue = document.get("permissions");
        if (permissionsValue != null) {
            if (permissionsValue instanceof Map<?, ?> permissions) {
                allow = parseRules(permissions.get("allow"), Decision.ALLOW, "permissions.allow", path, warnings);
                deny = parseRules(permissions.get("deny"), Decision.DENY, "permissions.deny", path, warnings);
            } else {
                warnings.add(warning(path, "permissions 必须是对象，已忽略权限规则"));
            }
        }
        return new LoadedSettings(new Settings(defaultMode, allow, deny), document, warnings);
    }

    private static String parseMode(Object value, Path path, List<String> warnings) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            warnings.add(warning(path, "defaultMode 必须是字符串，已忽略"));
            return "";
        }
        return Mode.parseConfigurable(text)
                .map(Mode::wireName)
                .orElseGet(() -> {
                    warnings.add(warning(path, "defaultMode 无效，已忽略"));
                    return "";
                });
    }

    private static List<String> parseRules(Object value, Decision decision, String field, Path path,
                                           List<String> warnings) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            warnings.add(warning(path, field + " 必须是数组，已忽略"));
            return List.of();
        }
        List<String> valid = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || Rule.parse(text, decision).isEmpty()) {
                warnings.add(warning(path, field + " 包含无效或非 Bash 规则，已跳过"));
                continue;
            }
            valid.add(text.strip());
        }
        return List.copyOf(valid);
    }

    private static LinkedHashMap<String, Object> stringKeyMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }

    private static String warning(Path path, String message) {
        return "权限配置 " + path.toAbsolutePath().normalize() + ": " + message;
    }

    public record LoadedSettings(Settings settings, Map<String, Object> document, List<String> warnings) {
        public LoadedSettings {
            settings = settings == null ? Settings.empty() : settings;
            document = document == null ? Map.of() : new LinkedHashMap<>(document);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static LoadedSettings empty() {
            return new LoadedSettings(Settings.empty(), new LinkedHashMap<>(), List.of());
        }
    }
}

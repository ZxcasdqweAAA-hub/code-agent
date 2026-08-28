package com.study.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolSupport {
    private ToolSupport() {
    }

    public static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(required));
        return schema;
    }

    public static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    public static String requireString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.toString();
    }

    public static String optionalString(Map<String, Object> args, String name, String defaultValue) {
        Object value = args.get(name);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return value.toString();
    }
}

package com.study.tool;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

final class GlobMatcher {
    private GlobMatcher() {
    }

    static boolean matches(String pattern, Path relativePath) {
        String normalizedPattern = normalize(pattern);
        String normalizedPath = normalize(relativePath.toString());
        if (jdkMatches(normalizedPattern, normalizedPath)) {
            return true;
        }
        if (normalizedPattern.startsWith("**/")) {
            String withoutLeadingGlob = normalizedPattern.substring(3);
            return jdkMatches(withoutLeadingGlob, normalizedPath) || normalizedPath.endsWith("/" + withoutLeadingGlob);
        }
        return false;
    }

    private static boolean jdkMatches(String pattern, String path) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        return matcher.matches(Path.of(path));
    }

    private static String normalize(String value) {
        return value.replace('\\', '/');
    }
}

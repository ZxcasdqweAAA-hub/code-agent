package com.study.llm;

public record Usage(long inputTokens, long outputTokens, long cacheWrite, long cacheRead) {
    public Usage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }
}
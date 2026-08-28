package com.study.llm;

public record Usage(long inputTokens, long outputTokens, long cacheWrite, long cacheRead,
                    long contextInputTokens) {
    public Usage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0, 0, inputTokens);
    }

    public Usage(long inputTokens, long outputTokens, long cacheWrite, long cacheRead) {
        this(inputTokens, outputTokens, cacheWrite, cacheRead, inputTokens);
    }
}

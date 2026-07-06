package com.study.llm;

/** 本轮请求的输入/输出 token 用量。 */
public record Usage(long inputTokens, long outputTokens) {
}

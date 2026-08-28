package com.study.llm;

import com.study.config.ProviderConfig;

public interface LlmClient {
    LlmStream stream(Request request);

    String name();

    String model();

    static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return switch (cfg.getProtocol()) {
            case "anthropic" -> new AnthropicClient(cfg, systemPrompt);
            case "openai", "openai-compat" -> new OpenAiClient(cfg, systemPrompt);
            default -> throw new IllegalArgumentException("Unsupported protocol: " + cfg.getProtocol());
        };
    }
}

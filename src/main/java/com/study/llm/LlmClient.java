package com.study.llm;

import com.study.config.ProviderConfig;
import com.study.conversation.ConversationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public interface LlmClient {
    BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools);

    BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools, String systemSuffix);

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

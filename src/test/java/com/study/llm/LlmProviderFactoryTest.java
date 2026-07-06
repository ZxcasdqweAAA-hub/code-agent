package com.study.llm;

import com.study.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LlmProviderFactoryTest {
    @Test
    void createsOpenAiClient() {
        ProviderConfig config = provider("openai", "DeepSeek", "deepseek-chat");

        LlmClient client = LlmClient.create(config, "system");

        assertInstanceOf(OpenAiClient.class, client);
        assertEquals("DeepSeek", client.name());
        assertEquals("deepseek-chat", client.model());
    }

    @Test
    void createsAnthropicClient() {
        ProviderConfig config = provider("anthropic", "Anthropic", "claude");

        LlmClient client = LlmClient.create(config, "system");

        assertInstanceOf(AnthropicClient.class, client);
        assertEquals("Anthropic", client.name());
        assertEquals("claude", client.model());
    }

    private ProviderConfig provider(String protocol, String name, String model) {
        ProviderConfig config = new ProviderConfig();
        config.setProtocol(protocol);
        config.setName(name);
        config.setModel(model);
        config.setBaseUrl("https://example.com");
        config.setApiKey("test-key");
        return config;
    }
}

package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ProviderRouter 单元测试。
 */
class ProviderRouterTest {

    @Test
    void shouldUseOpenAiWhenAutoAndApiKeyExists() {
        // auto 模式且存在 key，应该优先走 openai。
        AppProperties properties = new AppProperties();
        properties.getOpenai().setApiKey("sk-demo");
        ProviderRouter router = new ProviderRouter(properties);

        assertEquals("openai", router.pickProvider("auto"));
    }

    @Test
    void shouldUseOllamaWhenAutoAndApiKeyMissing() {
        // auto 模式无 key，回退到 ollama。
        AppProperties properties = new AppProperties();
        ProviderRouter router = new ProviderRouter(properties);

        assertEquals("ollama", router.pickProvider("auto"));
    }

    @Test
    void shouldRejectOpenAiWhenApiKeyMissing() {
        // 显式 openai 且无 key，直接报错。
        AppProperties properties = new AppProperties();
        ProviderRouter router = new ProviderRouter(properties);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> router.pickProvider("openai"));
        assertEquals("provider=openai requires OPENAI_API_KEY", exception.getMessage());
    }
}

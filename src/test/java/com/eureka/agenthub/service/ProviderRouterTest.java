package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderRouterTest {

    @Test
    void shouldUseOpenAiWhenAutoAndApiKeyExists() {
        AppProperties properties = new AppProperties();
        properties.getOpenai().setApiKey("sk-demo");
        ProviderRouter router = new ProviderRouter(properties);

        assertEquals("openai", router.pickProvider("auto"));
    }

    @Test
    void shouldUseOllamaWhenAutoAndApiKeyMissing() {
        AppProperties properties = new AppProperties();
        ProviderRouter router = new ProviderRouter(properties);

        assertEquals("ollama", router.pickProvider("auto"));
    }

    @Test
    void shouldRejectOpenAiWhenApiKeyMissing() {
        AppProperties properties = new AppProperties();
        ProviderRouter router = new ProviderRouter(properties);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> router.pickProvider("openai"));
        assertEquals("provider=openai requires OPENAI_API_KEY", exception.getMessage());
    }
}

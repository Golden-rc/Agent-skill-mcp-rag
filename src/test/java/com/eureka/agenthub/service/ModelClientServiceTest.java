package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelClientServiceTest {

    @Test
    void shouldFailFastWhenOpenAiEmbeddingWithoutApiKey() {
        AppProperties properties = new AppProperties();
        properties.getRag().setEmbeddingProvider("openai");
        properties.getRag().setEmbeddingModel("text-embedding-3-small");
        properties.getRag().setEmbeddingDimension(1536);
        properties.getOpenai().setApiKey("");

        assertThrows(IllegalStateException.class, () -> new ModelClientService(properties));
    }
}

package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProviderRouter {

    private final AppProperties appProperties;

    public ProviderRouter(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public String pickProvider(String requestProvider) {
        String provider = requestProvider == null ? "auto" : requestProvider.trim().toLowerCase();
        return switch (provider) {
            case "ollama" -> "ollama";
            case "openai" -> {
                ensureOpenaiConfigured();
                yield "openai";
            }
            case "auto" -> StringUtils.hasText(appProperties.getOpenai().getApiKey()) ? "openai" : "ollama";
            default -> throw new IllegalArgumentException("unsupported provider: " + requestProvider);
        };
    }

    private void ensureOpenaiConfigured() {
        if (!StringUtils.hasText(appProperties.getOpenai().getApiKey())) {
            throw new IllegalArgumentException("provider=openai requires OPENAI_API_KEY");
        }
    }
}

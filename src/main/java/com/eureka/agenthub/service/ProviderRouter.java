package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
/**
 * 模型路由器。
 * <p>
 * 根据请求中的 provider 参数和当前配置，决定最终走 openai 还是 ollama。
 */
public class ProviderRouter {

    private final AppProperties appProperties;

    public ProviderRouter(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public String pickProvider(String requestProvider) {
        // 统一归一化输入，避免大小写和空格问题。
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
        // 显式 openai 模式下必须有 API Key。
        if (!StringUtils.hasText(appProperties.getOpenai().getApiKey())) {
            throw new IllegalArgumentException("provider=openai requires OPENAI_API_KEY");
        }
    }
}

package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelClientService {

    private final AppProperties appProperties;

    public ModelClientService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public String chat(String provider, List<ChatMessage> messages) {
        return switch (provider) {
            case "ollama" -> chatWithOllama(messages);
            case "openai" -> chatWithOpenAiCompatible(messages);
            default -> throw new IllegalArgumentException("unsupported provider: " + provider);
        };
    }

    public List<Double> embed(String text) {
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(appProperties.getOllama().getBaseUrl())
                    .build();

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", appProperties.getOllama().getEmbeddingModel());
            payload.put("prompt", text);

            JsonNode body = client.post()
                    .uri("/api/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || body.get("embedding") == null || !body.get("embedding").isArray()) {
                throw new IllegalStateException("ollama embedding response is invalid");
            }

            List<Double> vector = new ArrayList<>();
            for (JsonNode node : body.get("embedding")) {
                vector.add(node.asDouble());
            }
            return vector;
        } catch (Exception ignored) {
            return deterministicEmbedding(text, 768);
        }
    }

    private List<Double> deterministicEmbedding(String text, int dimension) {
        List<Double> vector = new ArrayList<>(dimension);
        long seed = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) {
            seed = 31 * seed + text.charAt(i);
        }
        for (int i = 0; i < dimension; i++) {
            long value = seed ^ (seed << 13) ^ (seed >>> 7) ^ (seed << 17) ^ i;
            double normalized = ((value & 0x7fffffffL) % 2000) / 1000.0 - 1.0;
            vector.add(normalized);
        }
        return vector;
    }

    private String chatWithOllama(List<ChatMessage> messages) {
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(appProperties.getOllama().getBaseUrl())
                    .build();

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", appProperties.getOllama().getChatModel());
            payload.put("stream", false);
            payload.put("messages", messages);

            JsonNode body = client.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode message = body == null ? null : body.get("message");
            if (message == null || message.get("content") == null) {
                throw new IllegalStateException("ollama chat response is invalid");
            }
            return message.get("content").asText();
        } catch (Exception ignored) {
            return fallbackAnswer(messages);
        }
    }

    private String chatWithOpenAiCompatible(List<ChatMessage> messages) {
        String apiKey = appProperties.getOpenai().getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("OPENAI_API_KEY is required for provider=openai");
        }

        RestClient client = RestClient.builder()
                .baseUrl(appProperties.getOpenai().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", appProperties.getOpenai().getChatModel());
        payload.put("messages", messages);

        try {
            JsonNode body = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode choices = body == null ? null : body.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("openai compatible response is invalid");
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode()) {
                throw new IllegalStateException("openai compatible response missing message.content");
            }
            return content.asText();
        } catch (Exception ignored) {
            return fallbackAnswer(messages);
        }
    }

    private String fallbackAnswer(List<ChatMessage> messages) {
        String userInput = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                userInput = messages.get(i).content();
                break;
            }
        }
        return "当前模型服务暂不可用，已使用降级回答。\n建议行动：\n1. 明确目标和验收标准\n2. 先搭建RAG与MCP最小闭环\n3. 加入Redis记忆与安全策略\n\n你的问题是：" + userInput;
    }
}

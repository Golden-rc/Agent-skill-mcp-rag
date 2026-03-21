package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * 模型访问服务。
 * <p>
 * 统一封装 Ollama 与 OpenAI 兼容接口调用，并提供 BigModel token 适配和降级逻辑。
 */
public class ModelClientService {

    private final AppProperties appProperties;

    public ModelClientService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public String chat(String provider, List<ChatMessage> messages) {
        // 根据上层路由结果分发到不同模型实现。
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
            // 当本地 embedding 服务不可用时，用确定性向量兜底，保证流程可演示。
            return deterministicEmbedding(text, 768);
        }
    }

    private List<Double> deterministicEmbedding(String text, int dimension) {
        // 伪向量仅用于降级，不用于生产质量检索。
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
            // 本地模型不可用时回退到固定模板，避免接口直接报 500。
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
                .build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", appProperties.getOpenai().getChatModel());
        payload.put("messages", messages);
        payload.put("stream", false);

        try {
            // 优先尝试把 apiKey 原样作为 Bearer（兼容 BigModel 直接 Bearer id.secret）。
            JsonNode body = requestChatCompletion(client, payload, "Bearer " + apiKey);
            return extractContent(body);
        } catch (Exception firstError) {
            try {
                // 若直传失败，再尝试 BigModel JWT 风格 token。
                String fallbackToken = resolveBearerToken(apiKey, appProperties.getOpenai().getBaseUrl());
                if (("Bearer " + apiKey).equals("Bearer " + fallbackToken)) {
                    throw firstError;
                }
                JsonNode body = requestChatCompletion(client, payload, "Bearer " + fallbackToken);
                return extractContent(body);
            } catch (Exception ignored) {
                return fallbackAnswer(messages);
            }
        }
    }

    private JsonNode requestChatCompletion(RestClient client, Map<String, Object> payload, String authorization) {
        // 保持请求结构与 curl/openai 兼容格式一致。
        return client.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(HttpHeaders.USER_AGENT, "agent-hub/1.0")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
    }

    private String extractContent(JsonNode body) {
        // 支持 `message.content` 为字符串或分段数组两种结构。
        JsonNode choices = body == null ? null : body.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("openai compatible response is invalid");
        }
        JsonNode contentNode = choices.get(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new IllegalStateException("openai compatible response missing message.content");
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                JsonNode textNode = part.path("text");
                if (!textNode.isMissingNode() && textNode.isTextual()) {
                    sb.append(textNode.asText());
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        return contentNode.toString();
    }

    private String fallbackAnswer(List<ChatMessage> messages) {
        // 降级回答统一保留用户问题，便于前端排查。
        String userInput = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                userInput = messages.get(i).content();
                break;
            }
        }
        return "当前模型服务暂不可用，已使用降级回答。\n建议行动：\n1. 明确目标和验收标准\n2. 先搭建RAG与MCP最小闭环\n3. 加入Redis记忆与安全策略\n\n你的问题是：" + userInput;
    }

    private String resolveBearerToken(String apiKey, String baseUrl) {
        // 非 BigModel 网关不做 token 转换。
        if (!StringUtils.hasText(baseUrl) || !baseUrl.contains("bigmodel.cn")) {
            return apiKey;
        }
        if (!apiKey.contains(".")) {
            return apiKey;
        }
        return buildBigModelToken(apiKey);
    }

    private String buildBigModelToken(String apiKey) {
        String[] parts = apiKey.split("\\.", 2);
        if (parts.length != 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
            throw new IllegalArgumentException("invalid OPENAI_API_KEY format for bigmodel");
        }

        String apiId = parts[0];
        String apiSecret = parts[1];
        // exp 与 timestamp 为 BigModel token 所需字段。
        long nowMillis = Instant.now().toEpochMilli();
        long expSeconds = Instant.now().getEpochSecond() + 3600;

        String headerJson = "{\"alg\":\"HS256\",\"sign_type\":\"SIGN\"}";
        String payloadJson = "{\"api_key\":\"" + apiId + "\",\"exp\":" + expSeconds + ",\"timestamp\":" + nowMillis + "}";

        String header = base64Url(headerJson);
        String payload = base64Url(payloadJson);
        String toSign = header + "." + payload;
        String signature = hmacSha256Base64Url(toSign, apiSecret);
        return toSign + "." + signature;
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256Base64Url(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sign = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sign);
        } catch (Exception e) {
            // token 生成失败时抛出明确错误，便于定位配置问题。
            throw new IllegalStateException("failed to build bigmodel token", e);
        }
    }
}

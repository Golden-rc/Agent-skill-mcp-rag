package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ToolCallRequest;
import com.eureka.agenthub.model.ToolChatResult;
import com.eureka.agenthub.model.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LangChain4j 模型访问层。
 * <p>
 * 统一封装 chat/embedding，并根据 provider 动态路由到 OpenAI 兼容模型或 Ollama。
 */
@Service
public class ModelClientService {

    private final ChatLanguageModel ollamaChatModel;
    private final ChatLanguageModel openAiChatModel;
    private final EmbeddingModel ollamaEmbeddingModel;
    private final EmbeddingModel openAiEmbeddingModel;
    private final AppProperties appProperties;
    private final String ragEmbeddingProvider;
    private final int ragEmbeddingDimension;
    private final RestClient openAiRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ModelClientService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.ragEmbeddingProvider = normalizeEmbeddingProvider(appProperties.getRag().getEmbeddingProvider());
        this.ragEmbeddingDimension = appProperties.getRag().getEmbeddingDimension();

        if ("openai".equals(this.ragEmbeddingProvider)
                && !StringUtils.hasText(appProperties.getOpenai().getApiKey())) {
            throw new IllegalStateException("RAG embedding provider is openai but OPENAI_API_KEY is empty");
        }

        String ragEmbeddingModel = appProperties.getRag().getEmbeddingModel();
        String openAiEmbeddingModelName = StringUtils.hasText(ragEmbeddingModel)
                ? ragEmbeddingModel
                : "text-embedding-3-small";
        String ollamaEmbeddingModelName = StringUtils.hasText(ragEmbeddingModel)
                ? ragEmbeddingModel
                : appProperties.getOllama().getEmbeddingModel();

        this.ollamaChatModel = OllamaChatModel.builder()
                .baseUrl(appProperties.getOllama().getBaseUrl())
                .modelName(appProperties.getOllama().getChatModel())
                .temperature(0.2)
                .build();

        this.openAiChatModel = OpenAiChatModel.builder()
                .baseUrl(appProperties.getOpenai().getBaseUrl())
                .apiKey(appProperties.getOpenai().getApiKey())
                .modelName(appProperties.getOpenai().getChatModel())
                .temperature(0.2)
                .build();

        this.ollamaEmbeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(appProperties.getOllama().getBaseUrl())
                .modelName(ollamaEmbeddingModelName)
                .build();

        this.openAiEmbeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(appProperties.getOpenai().getBaseUrl())
                .apiKey(appProperties.getOpenai().getApiKey())
                .modelName(openAiEmbeddingModelName)
                .build();

        this.openAiRestClient = RestClient.builder()
                .baseUrl(appProperties.getOpenai().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + appProperties.getOpenai().getApiKey())
                .build();
    }

    /**
     * 生成聊天回答。
     */
    public String chat(String provider, List<ChatMessage> messages) {
        String prompt = toPrompt(messages);
        try {
            return switch (provider) {
                case "ollama" -> ollamaChatModel.generate(prompt);
                case "openai" -> openAiChatModel.generate(prompt);
                default -> throw new IllegalArgumentException("unsupported provider: " + provider);
            };
        } catch (Exception e) {
            return fallbackAnswer(messages);
        }
    }

    /**
     * 用于 auto 模式的意图分类。
     */
    public String classifyMode(String provider, String message) {
        String prompt = "You are an intent classifier. Output exactly one word: direct or rag. " +
                "Use direct for simple math/chit-chat/short factual answer. " +
                "Use rag for requests likely requiring knowledge retrieval context.\n\nUser: " + message;
        try {
            String result = ("openai".equals(provider) ? openAiChatModel : ollamaChatModel)
                    .generate(prompt)
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (result.contains("direct")) {
                return "direct";
            }
            if (result.contains("rag")) {
                return "rag";
            }
        } catch (Exception ignored) {
        }
        return "rag";
    }

    /**
     * 走 OpenAI 工具调用协议（tools/tool_calls）的一轮对话。
     */
    public ToolChatResult chatWithTools(String provider,
                                        List<Map<String, Object>> messages,
                                        List<ToolDefinition> tools) {
        if (!"openai".equals(provider)) {
            throw new IllegalArgumentException("tool protocol only supports openai provider currently");
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", appProperties.getOpenai().getChatModel());
            payload.put("temperature", 0.1);
            payload.put("messages", messages);
            payload.put("tool_choice", "auto");
            payload.put("tools", toOpenAiTools(tools));

            JsonNode response = openAiRestClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return new ToolChatResult("", List.of());
            }

            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return new ToolChatResult("", List.of());
            }

            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText("");
            List<ToolCallRequest> toolCalls = parseToolCalls(message.path("tool_calls"));
            return new ToolChatResult(content, toolCalls);
        } catch (Exception e) {
            throw new IllegalStateException("failed to call chat with tools: " + rootCauseMessage(e), e);
        }
    }

    /**
     * 生成 embedding 向量。
     */
    public List<Double> embed(String text) {
        try {
            EmbeddingModel model = switch (ragEmbeddingProvider) {
                case "openai" -> {
                    yield openAiEmbeddingModel;
                }
                case "ollama" -> ollamaEmbeddingModel;
                default -> throw new IllegalArgumentException("unsupported rag embedding provider: " + ragEmbeddingProvider);
            };
            float[] vector = model.embed(text).content().vector();
            if (vector.length != ragEmbeddingDimension) {
                throw new IllegalStateException("embedding dimension mismatch, expected="
                        + ragEmbeddingDimension + " actual=" + vector.length);
            }
            List<Double> result = new ArrayList<>(vector.length);
            for (float v : vector) {
                result.add((double) v);
            }
            return result;
        } catch (Exception e) {
            String rootMessage = rootCauseMessage(e);
            if (isModelNotFoundError(rootMessage)) {
                throw new IllegalArgumentException("embedding model not found: "
                        + appProperties.getRag().getEmbeddingModel()
                        + ". Please set RAG_EMBED_MODEL to a valid model for your OPENAI_BASE_URL.", e);
            }
            throw new IllegalStateException("failed to generate embedding: " + rootMessage, e);
        }
    }

    private String toPrompt(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            sb.append(msg.role().toUpperCase(Locale.ROOT))
                    .append(":\n")
                    .append(msg.content())
                    .append("\n\n");
        }
        return sb.toString();
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

    private String normalizeEmbeddingProvider(String provider) {
        String normalized = provider == null ? "openai" : provider.trim().toLowerCase(Locale.ROOT);
        if (!"openai".equals(normalized) && !"ollama".equals(normalized)) {
            throw new IllegalArgumentException("unsupported rag embedding provider: " + provider);
        }
        return normalized;
    }

    private List<Map<String, Object>> toOpenAiTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> output = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> function = new HashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.inputSchema());

            Map<String, Object> entry = new HashMap<>();
            entry.put("type", "function");
            entry.put("function", function);
            output.add(entry);
        }
        return output;
    }

    private List<ToolCallRequest> parseToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }
        List<ToolCallRequest> calls = new ArrayList<>();
        for (int i = 0; i < toolCallsNode.size(); i++) {
            JsonNode node = toolCallsNode.get(i);
            String id = node.path("id").asText("");
            if (id.isBlank()) {
                id = "tool-call-" + i;
            }
            JsonNode functionNode = node.path("function");
            String name = functionNode.path("name").asText("");
            String argumentsJson = functionNode.path("arguments").asText("{}");
            Map<String, Object> arguments = parseArguments(argumentsJson);
            if (!name.isBlank()) {
                calls.add(new ToolCallRequest(id, name, arguments));
            }
        }
        return calls;
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        try {
            if (!StringUtils.hasText(argumentsJson)) {
                return Map.of();
            }
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("text", argumentsJson == null ? "" : argumentsJson);
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return StringUtils.hasText(message) ? message : current.getClass().getSimpleName();
    }

    private boolean isModelNotFoundError(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("模型不存在")
                || normalized.contains("model not found")
                || normalized.contains("model does not exist")
                || normalized.contains("code\":\"1211\"");
    }
}

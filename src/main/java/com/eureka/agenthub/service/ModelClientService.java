package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

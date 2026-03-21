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

    public ModelClientService(AppProperties appProperties) {
        this.appProperties = appProperties;

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
                .modelName(appProperties.getOllama().getEmbeddingModel())
                .build();

        this.openAiEmbeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(appProperties.getOpenai().getBaseUrl())
                .apiKey(appProperties.getOpenai().getApiKey())
                .modelName("text-embedding-3-small")
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
            EmbeddingModel model = shouldUseOpenAiEmbedding() ? openAiEmbeddingModel : ollamaEmbeddingModel;
            float[] vector = model.embed(text).content().vector();
            List<Double> result = new ArrayList<>(vector.length);
            for (float v : vector) {
                result.add((double) v);
            }
            return result;
        } catch (Exception ignored) {
            return deterministicEmbedding(text, 768);
        }
    }

    private boolean shouldUseOpenAiEmbedding() {
        return StringUtils.hasText(appProperties.getOpenai().getApiKey());
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
}

package com.eureka.agenthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
/**
 * 应用自定义配置。
 * <p>
 * 通过 `application.yml` 的 `app.*` 前缀映射，集中管理模型、MCP、记忆等参数。
 */
public class AppProperties {

    private final Ollama ollama = new Ollama();
    private final Openai openai = new Openai();
    private final Mcp mcp = new Mcp();
    private final Memory memory = new Memory();

    public Ollama getOllama() {
        return ollama;
    }

    public Openai getOpenai() {
        return openai;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Memory getMemory() {
        return memory;
    }

    public static class Ollama {
        /** Ollama 服务地址。 */
        private String baseUrl = "http://localhost:11434";
        /** 聊天模型名称。 */
        private String chatModel = "qwen3:4b";
        /** Embedding 模型名称。 */
        private String embeddingModel = "nomic-embed-text:latest";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }
    }

    public static class Openai {
        /** OpenAI 兼容 API 网关地址。 */
        private String baseUrl = "https://api.openai.com/v1";
        /** API Key（支持 BigModel 的 id.secret）。 */
        private String apiKey = "";
        /** 对话模型名称。 */
        private String chatModel = "gpt-4o-mini";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }
    }

    public static class Mcp {
        /** MCP 服务地址。 */
        private String baseUrl = "http://localhost:8090";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Memory {
        /** Redis 中保留的最大历史消息条数。 */
        private int maxHistoryMessages = 10;
        /** 会话历史 TTL（小时）。 */
        private int historyTtlHours = 24;

        public int getMaxHistoryMessages() {
            return maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages;
        }

        public int getHistoryTtlHours() {
            return historyTtlHours;
        }

        public void setHistoryTtlHours(int historyTtlHours) {
            this.historyTtlHours = historyTtlHours;
        }
    }
}

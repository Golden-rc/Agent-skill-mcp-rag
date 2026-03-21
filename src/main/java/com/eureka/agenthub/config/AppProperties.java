package com.eureka.agenthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
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
        private String baseUrl = "http://localhost:11434";
        private String chatModel = "qwen3:4b";
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
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
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
        private String baseUrl = "http://localhost:8090";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Memory {
        private int maxHistoryMessages = 10;
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

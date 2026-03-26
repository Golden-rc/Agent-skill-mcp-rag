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
    private final Rag rag = new Rag();
    private final Chat chat = new Chat();

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

    public Rag getRag() {
        return rag;
    }

    public Chat getChat() {
        return chat;
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
        private int maxHistoryMessages = 50;
        /** 会话历史 TTL（小时）。 */
        private int historyTtlHours = 168;
        /** direct 模式下参与上下文的最近消息条数。 */
        private int directHistoryMessages = 8;

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

        public int getDirectHistoryMessages() {
            return directHistoryMessages;
        }

        public void setDirectHistoryMessages(int directHistoryMessages) {
            this.directHistoryMessages = directHistoryMessages;
        }
    }

    public static class Rag {
        /** Embedding provider: openai / ollama. */
        private String embeddingProvider = "openai";
        /** Embedding model name for selected provider. */
        private String embeddingModel = "embedding-2";
        /** Embedding vector dimension used by pgvector schema. */
        private int embeddingDimension = 1024;
        /** Default chunk size for ingest. */
        private int chunkSize = 500;
        /** Overlap size between chunks. */
        private int chunkOverlap = 80;
        /** Minimum chunk size before merge. */
        private int chunkMinSize = 120;
        /** Candidate recall size before rerank. */
        private int recallTopK = 20;
        /** Final topK after rerank. */
        private int finalTopK = 5;
        /** Minimal rerank score to keep in context. */
        private double minScore = 0.35;
        /** Max citation content length returned by chat API. */
        private int citationMaxChars = 50;
        /** RAG 上下文拼接的最大总字符数预算。 */
        private int contextMaxChars = 2600;
        /** 单条 RAG 命中在 prompt 中的最大字符数。 */
        private int contextChunkMaxChars = 700;
        /** 判定为“证据不足”时所需的最小命中条数。 */
        private int minEvidenceCount = 1;

        public String getEmbeddingProvider() {
            return embeddingProvider;
        }

        public void setEmbeddingProvider(String embeddingProvider) {
            this.embeddingProvider = embeddingProvider;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDimension() {
            return embeddingDimension;
        }

        public void setEmbeddingDimension(int embeddingDimension) {
            this.embeddingDimension = embeddingDimension;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getChunkMinSize() {
            return chunkMinSize;
        }

        public void setChunkMinSize(int chunkMinSize) {
            this.chunkMinSize = chunkMinSize;
        }

        public int getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(int recallTopK) {
            this.recallTopK = recallTopK;
        }

        public int getFinalTopK() {
            return finalTopK;
        }

        public void setFinalTopK(int finalTopK) {
            this.finalTopK = finalTopK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public int getCitationMaxChars() {
            return citationMaxChars;
        }

        public void setCitationMaxChars(int citationMaxChars) {
            this.citationMaxChars = citationMaxChars;
        }

        public int getContextMaxChars() {
            return contextMaxChars;
        }

        public void setContextMaxChars(int contextMaxChars) {
            this.contextMaxChars = contextMaxChars;
        }

        public int getContextChunkMaxChars() {
            return contextChunkMaxChars;
        }

        public void setContextChunkMaxChars(int contextChunkMaxChars) {
            this.contextChunkMaxChars = contextChunkMaxChars;
        }

        public int getMinEvidenceCount() {
            return minEvidenceCount;
        }

        public void setMinEvidenceCount(int minEvidenceCount) {
            this.minEvidenceCount = minEvidenceCount;
        }
    }

    public static class Chat {
        /** 编排模式：classic / agent。 */
        private String orchestrator = "classic";
        /** 是否启用 agent 编排器。 */
        private boolean agentEnabled = false;
        /** 是否启用协议化工具调用（tool_calls）。 */
        private boolean toolCallingEnabled = true;
        /** 当前仅在 openai provider 启用协议化工具调用。 */
        private boolean toolCallingOpenaiOnly = true;
        /** 单次对话最多工具回合数。 */
        private int maxToolRounds = 3;

        public boolean isToolCallingEnabled() {
            return toolCallingEnabled;
        }

        public void setToolCallingEnabled(boolean toolCallingEnabled) {
            this.toolCallingEnabled = toolCallingEnabled;
        }

        public boolean isToolCallingOpenaiOnly() {
            return toolCallingOpenaiOnly;
        }

        public void setToolCallingOpenaiOnly(boolean toolCallingOpenaiOnly) {
            this.toolCallingOpenaiOnly = toolCallingOpenaiOnly;
        }

        public int getMaxToolRounds() {
            return maxToolRounds;
        }

        public void setMaxToolRounds(int maxToolRounds) {
            this.maxToolRounds = maxToolRounds;
        }

        public String getOrchestrator() {
            return orchestrator;
        }

        public void setOrchestrator(String orchestrator) {
            this.orchestrator = orchestrator;
        }

        public boolean isAgentEnabled() {
            return agentEnabled;
        }

        public void setAgentEnabled(boolean agentEnabled) {
            this.agentEnabled = agentEnabled;
        }
    }
}

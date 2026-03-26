package com.eureka.agenthub.model;

import jakarta.validation.constraints.NotBlank;

/**
 * /chat 请求体。
 */
public class ChatRequest {

    @NotBlank
    /** 会话 ID，用于 Redis 记忆隔离。 */
    private String sessionId;
    @NotBlank
    /** 用户输入内容。 */
    private String message;
    /** 模型提供方：auto/openai/ollama。 */
    private String provider = "auto";
    /** 回答模式：auto/direct/rag。 */
    private String mode = "auto";
    /** 编排模式覆盖：classic/agent（可选）。 */
    private String orchestrator = "";
    /** 前端测试开关：强制尝试 tools/tool_calls 协议链路。 */
    private boolean toolTestMode = false;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getOrchestrator() {
        return orchestrator;
    }

    public void setOrchestrator(String orchestrator) {
        this.orchestrator = orchestrator;
    }

    public boolean isToolTestMode() {
        return toolTestMode;
    }

    public void setToolTestMode(boolean toolTestMode) {
        this.toolTestMode = toolTestMode;
    }
}

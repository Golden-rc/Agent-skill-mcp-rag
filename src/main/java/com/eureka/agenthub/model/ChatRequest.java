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
}

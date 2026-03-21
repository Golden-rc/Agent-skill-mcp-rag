package com.eureka.agenthub.model;

import jakarta.validation.constraints.NotBlank;

public class ChatRequest {

    @NotBlank
    private String sessionId;
    @NotBlank
    private String message;
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

package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;

/**
 * 对话编排器抽象。
 */
public interface ChatOrchestrator {

    /**
     * 编排器标识：classic / agent。
     */
    String key();

    ChatResponse chat(ChatRequest request, String provider);
}

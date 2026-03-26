package com.eureka.agenthub.port;

import com.eureka.agenthub.model.ChatMessage;

import java.util.List;

/**
 * 会话记忆能力抽象。
 */
public interface MemoryPort {

    List<ChatMessage> loadHistory(String sessionId);

    void append(String sessionId, ChatMessage message);
}

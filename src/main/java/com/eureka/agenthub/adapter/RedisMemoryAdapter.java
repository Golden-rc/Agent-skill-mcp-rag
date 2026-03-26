package com.eureka.agenthub.adapter;

import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.service.MemoryService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisMemoryAdapter implements MemoryPort {

    private final MemoryService memoryService;

    public RedisMemoryAdapter(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public List<ChatMessage> loadHistory(String sessionId) {
        return memoryService.loadHistory(sessionId);
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        memoryService.append(sessionId, message);
    }
}

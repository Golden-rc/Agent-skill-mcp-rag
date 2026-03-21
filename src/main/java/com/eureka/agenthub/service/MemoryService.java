package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class MemoryService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public MemoryService(StringRedisTemplate redisTemplate,
                         ObjectMapper objectMapper,
                         AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    public List<ChatMessage> loadHistory(String sessionId) {
        String key = key(sessionId);
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (String item : raw) {
            try {
                messages.add(objectMapper.readValue(item, ChatMessage.class));
            } catch (JsonProcessingException ignored) {
            }
        }
        return messages;
    }

    public void append(String sessionId, ChatMessage message) {
        String key = key(sessionId);
        try {
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to serialize chat message", e);
        }

        int max = appProperties.getMemory().getMaxHistoryMessages();
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > max) {
            redisTemplate.opsForList().trim(key, size - max, size - 1);
        }
        redisTemplate.expire(key, Duration.ofHours(appProperties.getMemory().getHistoryTtlHours()));
    }

    private String key(String sessionId) {
        return "chat:mem:" + sessionId;
    }
}

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
import java.util.Set;

@Service
/**
 * 会话记忆服务（Redis）。
 * <p>
 * 使用 `chat:mem:{sessionId}` 列表保存消息，支持 TTL、会话枚举、会话清理。
 */
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
                // 单条消息解析失败时跳过，避免整个会话读取失败。
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
            // 只保留最近 N 条消息，控制上下文长度。
            redisTemplate.opsForList().trim(key, size - max, size - 1);
        }
        // 每次写入都刷新会话 TTL。
        redisTemplate.expire(key, Duration.ofHours(appProperties.getMemory().getHistoryTtlHours()));
    }

    public List<SessionInfo> listSessions() {
        return listSessions("", "all", 50, 0);
    }

    public List<SessionInfo> listSessions(String query, String state, int limit, int offset) {
        // 扫描会话键用于后台运维页面。
        Set<String> keys = redisTemplate.keys("chat:mem:*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        String safeQuery = query == null ? "" : query.trim().toLowerCase();
        String safeState = state == null ? "all" : state.trim().toLowerCase();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);

        List<SessionInfo> sessions = new ArrayList<>();
        for (String fullKey : keys) {
            String sessionId = fullKey.replaceFirst("^chat:mem:", "");
            if (!safeQuery.isEmpty() && !sessionId.toLowerCase().contains(safeQuery)) {
                continue;
            }
            Long size = redisTemplate.opsForList().size(fullKey);
            Long ttl = redisTemplate.getExpire(fullKey);
            long ttlValue = ttl == null ? -1 : ttl;

            if ("active".equals(safeState) && ttlValue <= 0) {
                continue;
            }
            if ("expired".equals(safeState) && ttlValue > 0) {
                continue;
            }

            sessions.add(new SessionInfo(sessionId, size == null ? 0 : size.intValue(), ttlValue));
        }
        sessions.sort((a, b) -> Integer.compare(b.messageCount(), a.messageCount()));

        if (safeOffset >= sessions.size()) {
            return Collections.emptyList();
        }

        int toIndex = Math.min(sessions.size(), safeOffset + safeLimit);
        return new ArrayList<>(sessions.subList(safeOffset, toIndex));
    }

    public long countSessions(String query, String state) {
        Set<String> keys = redisTemplate.keys("chat:mem:*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }

        String safeQuery = query == null ? "" : query.trim().toLowerCase();
        String safeState = state == null ? "all" : state.trim().toLowerCase();

        long count = 0;
        for (String fullKey : keys) {
            String sessionId = fullKey.replaceFirst("^chat:mem:", "");
            if (!safeQuery.isEmpty() && !sessionId.toLowerCase().contains(safeQuery)) {
                continue;
            }
            Long ttl = redisTemplate.getExpire(fullKey);
            long ttlValue = ttl == null ? -1 : ttl;
            if ("active".equals(safeState) && ttlValue <= 0) {
                continue;
            }
            if ("expired".equals(safeState) && ttlValue > 0) {
                continue;
            }
            count++;
        }
        return count;
    }

    public int clearSession(String sessionId) {
        // 返回 1/0 便于前端展示是否真的删除。
        Boolean deleted = redisTemplate.delete(key(sessionId));
        return Boolean.TRUE.equals(deleted) ? 1 : 0;
    }

    /**
     * 会话简要信息。
     */
    public record SessionInfo(String sessionId, int messageCount, long ttlSeconds) {
    }

    private String key(String sessionId) {
        return "chat:mem:" + sessionId;
    }
}

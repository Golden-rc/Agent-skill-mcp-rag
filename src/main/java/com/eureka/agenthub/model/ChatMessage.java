package com.eureka.agenthub.model;

/**
 * 对话消息结构。
 * role 通常为 system/user/assistant。
 */
public record ChatMessage(String role, String content) {
}

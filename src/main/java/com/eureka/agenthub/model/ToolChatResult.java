package com.eureka.agenthub.model;

import java.util.List;

/**
 * 带工具协议的一轮模型响应。
 */
public record ToolChatResult(String assistantText,
                             List<ToolCallRequest> toolCalls) {
}

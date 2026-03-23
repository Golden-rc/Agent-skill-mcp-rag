package com.eureka.agenthub.model;

import java.util.Map;

/**
 * 大模型工具定义（OpenAI tools/function schema）。
 */
public record ToolDefinition(String name,
                             String description,
                             Map<String, Object> inputSchema) {
}

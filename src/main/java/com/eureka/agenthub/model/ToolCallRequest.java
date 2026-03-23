package com.eureka.agenthub.model;

import java.util.Map;

/**
 * 模型返回的工具调用请求。
 */
public record ToolCallRequest(String id,
                              String name,
                              Map<String, Object> arguments) {
}

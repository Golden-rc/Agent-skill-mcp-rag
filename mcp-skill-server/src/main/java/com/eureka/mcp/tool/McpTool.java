package com.eureka.mcp.tool;

import java.util.Map;

/**
 * MCP 工具统一接口。
 */
public interface McpTool {

    String name();

    String description();

    /**
     * 工具入参 schema（MCP tools/list 使用）。
     * 默认兼容现有 text 型工具。
     */
    default Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of(
                                "type", "string",
                                "description", "Input text for tool processing"
                        )
                ),
                "required", java.util.List.of("text")
        );
    }

    String execute(Map<String, Object> arguments);
}

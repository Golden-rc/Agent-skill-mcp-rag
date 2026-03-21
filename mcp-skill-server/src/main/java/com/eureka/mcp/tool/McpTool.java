package com.eureka.mcp.tool;

import java.util.Map;

/**
 * MCP 工具统一接口。
 */
public interface McpTool {

    String name();

    String description();

    String execute(Map<String, Object> arguments);
}

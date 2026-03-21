package com.eureka.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心。
 */
@Component
public class ToolRegistry {

    private final Map<String, McpTool> toolsByName;

    public ToolRegistry(List<McpTool> tools) {
        this.toolsByName = new LinkedHashMap<>();
        for (McpTool tool : tools) {
            toolsByName.put(tool.name(), tool);
        }
    }

    public List<Map<String, Object>> listToolMetadata() {
        return toolsByName.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "text", Map.of(
                                                "type", "string",
                                                "description", "Input text for tool processing"
                                        )
                                ),
                                "required", List.of("text")
                        )
                ))
                .toList();
    }

    public String execute(String toolName, Map<String, Object> arguments) {
        McpTool tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("tool not found: " + toolName);
        }
        return tool.execute(arguments);
    }
}

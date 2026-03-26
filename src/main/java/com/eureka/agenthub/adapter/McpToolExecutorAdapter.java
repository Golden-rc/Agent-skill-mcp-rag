package com.eureka.agenthub.adapter;

import com.eureka.agenthub.model.ToolDefinition;
import com.eureka.agenthub.port.ToolExecutorPort;
import com.eureka.agenthub.service.McpClientService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class McpToolExecutorAdapter implements ToolExecutorPort {

    private final McpClientService mcpClientService;

    public McpToolExecutorAdapter(McpClientService mcpClientService) {
        this.mcpClientService = mcpClientService;
    }

    @Override
    public List<ToolDefinition> listCallableTools() {
        return mcpClientService.listCallableTools();
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        return mcpClientService.callTool(toolName, arguments);
    }
}

package com.eureka.agenthub.port;

import com.eureka.agenthub.model.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * 工具执行能力抽象。
 */
public interface ToolExecutorPort {

    List<ToolDefinition> listCallableTools();

    String callTool(String toolName, Map<String, Object> arguments);
}

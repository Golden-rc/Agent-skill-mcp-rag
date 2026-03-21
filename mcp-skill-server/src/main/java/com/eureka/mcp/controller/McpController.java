package com.eureka.mcp.controller;

import com.eureka.mcp.tool.ToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/mcp")
/**
 * 最小 MCP 服务实现。
 * <p>
 * 支持 tools/list 和 tools/call 两个方法，便于主应用做工具调用演示。
 */
public class McpController {

    private final ToolRegistry toolRegistry;

    public McpController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @PostMapping
    /**
     * 统一 MCP JSON-RPC 入口。
     */
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        String method = String.valueOf(request.get("method"));
        Object id = request.getOrDefault("id", "1");

        if ("tools/list".equals(method)) {
            return ResponseEntity.ok(ok(id, Map.of(
                    "tools", toolRegistry.listToolMetadata()
            )));
        }

        if ("tools/call".equals(method)) {
            Map<String, Object> params = asMap(request.get("params"));
            String name = String.valueOf(params.get("name"));
            Map<String, Object> arguments = asMap(params.get("arguments"));

            String content;
            try {
                content = toolRegistry.execute(name, arguments);
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.ok(error(id, -32601, "tool not found: " + name));
            }

            return ResponseEntity.ok(ok(id, Map.of("content", content)));
        }

        return ResponseEntity.ok(error(id, -32601, "method not found: " + method));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        // 安全转换，避免 ClassCastException。
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Map<String, Object> ok(Object id, Map<String, Object> result) {
        // 构造 JSON-RPC 成功响应。
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        // 构造 JSON-RPC 错误响应。
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}

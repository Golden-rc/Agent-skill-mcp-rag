package com.eureka.mcp.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/mcp")
public class McpController {

    @PostMapping
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        String method = String.valueOf(request.get("method"));
        Object id = request.getOrDefault("id", "1");

        if ("tools/list".equals(method)) {
            return ResponseEntity.ok(ok(id, Map.of(
                    "tools", List.of(
                            Map.of("name", "summarize", "description", "Summarize a text"),
                            Map.of("name", "extract_todos", "description", "Extract action items from text")
                    )
            )));
        }

        if ("tools/call".equals(method)) {
            Map<String, Object> params = asMap(request.get("params"));
            String name = String.valueOf(params.get("name"));
            Map<String, Object> arguments = asMap(params.get("arguments"));
            String text = String.valueOf(arguments.getOrDefault("text", ""));

            String content;
            if ("summarize".equals(name)) {
                content = summarize(text);
            } else if ("extract_todos".equals(name)) {
                content = extractTodos(text);
            } else {
                return ResponseEntity.ok(error(id, -32601, "tool not found: " + name));
            }

            return ResponseEntity.ok(ok(id, Map.of("content", content)));
        }

        return ResponseEntity.ok(error(id, -32601, "method not found: " + method));
    }

    private String summarize(String text) {
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return "(empty text)";
        }
        int maxLen = Math.min(normalized.length(), 180);
        return "摘要: " + normalized.substring(0, maxLen) + (normalized.length() > maxLen ? "..." : "");
    }

    private String extractTodos(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n");
        List<String> todos = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") ||
                    lower.contains("todo") || trimmed.contains("待办") || trimmed.contains("行动项")) {
                todos.add(trimmed.replaceFirst("^[-*]\\s*", ""));
            }
        }
        if (todos.isEmpty()) {
            todos.add("未检测到明确待办，建议人工确认优先级和截止时间");
        }
        StringBuilder sb = new StringBuilder("待办清单:\n");
        for (int i = 0; i < todos.size(); i++) {
            sb.append(i + 1).append(". ").append(todos.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Map<String, Object> ok(Object id, Map<String, Object> result) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}

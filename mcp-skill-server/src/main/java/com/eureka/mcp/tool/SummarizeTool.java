package com.eureka.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SummarizeTool implements McpTool {

    @Override
    public String name() {
        return "summarize";
    }

    @Override
    public String description() {
        return "Summarize a text";
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String text = String.valueOf(arguments.getOrDefault("text", ""));
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return "(empty text)";
        }
        int maxLen = Math.min(normalized.length(), 180);
        return "摘要: " + normalized.substring(0, maxLen) + (normalized.length() > maxLen ? "..." : "");
    }
}

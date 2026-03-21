package com.eureka.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ExtractTodosTool implements McpTool {

    @Override
    public String name() {
        return "extract_todos";
    }

    @Override
    public String description() {
        return "Extract action items from text";
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String text = String.valueOf(arguments.getOrDefault("text", ""));
        String[] lines = text.replace("\r\n", "\n").split("\n");
        List<String> todos = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")
                    || lower.contains("todo") || trimmed.contains("待办") || trimmed.contains("行动项")) {
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
}

package com.eureka.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class SummarizeWithCitationsTool implements McpTool {

    @Override
    public String name() {
        return "summarize_with_citations";
    }

    @Override
    public String description() {
        return "Summarize text with explicit citation indexes";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of("type", "string", "description", "Input text"),
                        "maxBullets", Map.of("type", "integer", "description", "Summary bullets, default 3")
                ),
                "required", List.of("text")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String text = String.valueOf(arguments.getOrDefault("text", "")).trim();
            if (text.isBlank()) {
                return "摘要失败: text 不能为空";
            }
            int maxBullets = parseMaxBullets(arguments.get("maxBullets"));
            List<String> sentences = splitSentences(text);
            if (sentences.isEmpty()) {
                return "摘要失败: 无可提取句子";
            }

            List<Integer> selected = new ArrayList<>();
            for (int i = 0; i < sentences.size(); i++) {
                selected.add(i);
            }
            selected.sort(Comparator.comparingInt((Integer i) -> sentences.get(i).length()).reversed());
            if (selected.size() > maxBullets) {
                selected = selected.subList(0, maxBullets);
            }
            selected.sort(Comparator.naturalOrder());

            StringBuilder out = new StringBuilder("带引用摘要\n");
            for (int i = 0; i < selected.size(); i++) {
                int idx = selected.get(i);
                out.append("- ")
                        .append(limit(sentences.get(idx), 180))
                        .append(" [")
                        .append(idx + 1)
                        .append("]\n");
            }
            out.append("引用\n");
            for (int i = 0; i < selected.size(); i++) {
                int idx = selected.get(i);
                out.append("[")
                        .append(idx + 1)
                        .append("] ")
                        .append(limit(sentences.get(idx), 260))
                        .append("\n");
            }
            return out.toString().trim();
        } catch (Exception e) {
            return "摘要失败: " + safeMessage(e);
        }
    }

    private List<String> splitSentences(String text) {
        String[] parts = text.replace('\n', ' ').split("(?<=[。！？.!?])\\s+|\\s{2,}");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String v = part.trim();
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private int parseMaxBullets(Object value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return Math.max(1, Math.min(parsed, 8));
        } catch (Exception ignored) {
            return 3;
        }
    }

    private String limit(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "unknown" : message.replaceAll("\\s+", " ").trim();
    }
}

package com.eureka.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExtractEntitiesTool implements McpTool {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b|\\b\\d{1,2}月\\d{1,2}日\\b");
    private static final Pattern MONEY = Pattern.compile("(?:[¥$]\\s?\\d+(?:\\.\\d+)?)|(?:\\d+(?:\\.\\d+)?\\s?(?:元|美元|USD|CNY))");
    private static final Pattern PHONE = Pattern.compile("\\b(?:1[3-9]\\d{9}|\\+?\\d{2,4}[- ]?\\d{6,12})\\b");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "extract_entities";
    }

    @Override
    public String description() {
        return "Extract emails, URLs, dates, money and phones from text";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of("type", "string", "description", "Text to extract entities from")
                ),
                "required", List.of("text")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String text = String.valueOf(arguments.getOrDefault("text", ""));
            if (text.isBlank()) {
                return "实体提取失败: text 不能为空";
            }
            Map<String, Object> result = Map.of(
                    "emails", collect(EMAIL, text),
                    "urls", collect(URL, text),
                    "dates", collect(DATE, text),
                    "money", collect(MONEY, text),
                    "phones", collect(PHONE, text)
            );
            return "实体提取结果\n" + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return "实体提取失败: " + safeMessage(e);
        }
    }

    private List<String> collect(Pattern pattern, String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && out.size() < 50) {
            out.add(matcher.group());
        }
        return out.stream().toList();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "unknown" : message.replaceAll("\\s+", " ").trim();
    }
}

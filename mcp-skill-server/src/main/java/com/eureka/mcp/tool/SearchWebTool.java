package com.eureka.mcp.tool;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SearchWebTool implements McpTool {

    private static final Pattern LINK_PATTERN = Pattern.compile("(?is)<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private final RestClient restClient = RestClient.builder().build();

    @Override
    public String name() {
        return "search_web";
    }

    @Override
    public String description() {
        return "Search the web and return top results";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query text"),
                        "topK", Map.of("type", "integer", "description", "Number of results, default 5")
                ),
                "required", List.of("query")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
            if (query.isBlank()) {
                return "网页搜索失败: query 不能为空";
            }
            int topK = parseTopK(arguments.get("topK"));
            String html = restClient.get()
                    .uri("https://duckduckgo.com/html/?q=" + encode(query))
                    .retrieve()
                    .body(String.class);
            if (html == null || html.isBlank()) {
                return "网页搜索失败: 返回内容为空";
            }

            List<String> lines = new ArrayList<>();
            Matcher matcher = LINK_PATTERN.matcher(html);
            while (matcher.find() && lines.size() < topK) {
                String href = normalizeResultUrl(matcher.group(1));
                String title = compactText(matcher.group(2));
                if (title.isBlank() || href.isBlank()) {
                    continue;
                }
                if (!href.startsWith("http://") && !href.startsWith("https://")) {
                    continue;
                }
                lines.add((lines.size() + 1) + ". " + title + "\n   " + href);
            }

            if (lines.isEmpty()) {
                return "网页搜索失败: 未提取到有效结果";
            }
            return "网页搜索结果\nQuery: " + query + "\n" + String.join("\n", lines);
        } catch (Exception e) {
            return "网页搜索失败: " + safeMessage(e);
        }
    }

    private String normalizeResultUrl(String href) {
        String value = href == null ? "" : href.trim();
        if (value.contains("uddg=")) {
            String encoded = value.replaceFirst(".*uddg=", "");
            int and = encoded.indexOf('&');
            if (and > 0) {
                encoded = encoded.substring(0, and);
            }
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }
        return value;
    }

    private int parseTopK(Object value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return Math.max(1, Math.min(parsed, 10));
        } catch (Exception ignored) {
            return 5;
        }
    }

    private String compactText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "unknown" : message.replaceAll("\\s+", " ").trim();
    }
}

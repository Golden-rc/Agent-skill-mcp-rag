package com.eureka.mcp.tool;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReadWebpageStructuredTool implements McpTool {

    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern HEADING_PATTERN = Pattern.compile("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>");
    private static final Pattern LINK_PATTERN = Pattern.compile("(?is)<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>");

    private final RestClient restClient = RestClient.builder().build();

    @Override
    public String name() {
        return "read_webpage_structured";
    }

    @Override
    public String description() {
        return "Read webpage and return structured content fields";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "Target webpage URL"),
                        "maxChars", Map.of("type", "integer", "description", "Max main text chars, default 3000")
                ),
                "required", List.of("url")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String url = String.valueOf(arguments.getOrDefault("url", "")).trim();
            if (url.isBlank()) {
                return "结构化网页读取失败: url 不能为空";
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            int maxChars = parseMaxChars(arguments.get("maxChars"));
            String html = fetch(url);
            boolean mirror = false;
            if (isJsShell(html)) {
                try {
                    html = fetch("https://r.jina.ai/http://" + url.replaceFirst("^https?://", ""));
                    mirror = true;
                } catch (Exception ignored) {
                }
            }
            if (html == null || html.isBlank()) {
                return "结构化网页读取失败: 内容为空";
            }

            String title = matchFirst(TITLE_PATTERN, html);
            List<String> headings = collect(HEADING_PATTERN, html, 8);
            List<String> links = collectLinks(html, 10);
            String main = compact(html);
            if (main.length() > maxChars) {
                main = main.substring(0, maxChars) + "...";
            }

            return "结构化网页读取结果\nURL: " + url
                    + "\n来源: " + (mirror ? "readable-mirror" : "direct")
                    + "\nTitle: " + title
                    + "\nHeadings: " + String.join(" | ", headings)
                    + "\nLinks: " + String.join(" | ", links)
                    + "\nMainText: " + main;
        } catch (Exception e) {
            return "结构化网页读取失败: " + safeMessage(e);
        }
    }

    private String fetch(String url) {
        return restClient.get().uri(url).accept(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN).retrieve().body(String.class);
    }

    private List<String> collect(Pattern pattern, String html, int max) {
        List<String> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(html);
        while (matcher.find() && out.size() < max) {
            String value = compact(matcher.group(1));
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        if (out.isEmpty()) {
            out.add("(none)");
        }
        return out;
    }

    private List<String> collectLinks(String html, int max) {
        List<String> out = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(html);
        while (matcher.find() && out.size() < max) {
            String href = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String text = compact(matcher.group(2));
            if (href.isBlank()) {
                continue;
            }
            out.add((text.isBlank() ? "link" : text) + " -> " + href);
        }
        if (out.isEmpty()) {
            out.add("(none)");
        }
        return out;
    }

    private String matchFirst(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return compact(matcher.group(1));
        }
        return "(none)";
    }

    private boolean isJsShell(String html) {
        if (html == null || html.isBlank()) {
            return true;
        }
        String lower = html.toLowerCase();
        return lower.contains("javascript") && compact(html).length() < 150;
    }

    private String compact(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int parseMaxChars(Object value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return Math.max(500, Math.min(parsed, 12000));
        } catch (Exception ignored) {
            return 3000;
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "unknown" : message.replaceAll("\\s+", " ").trim();
    }
}

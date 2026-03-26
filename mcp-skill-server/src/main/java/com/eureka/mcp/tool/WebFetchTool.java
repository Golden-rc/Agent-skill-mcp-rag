package com.eureka.mcp.tool;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class WebFetchTool implements McpTool {

    private final RestClient restClient;

    public WebFetchTool() {
        this.restClient = RestClient.builder().build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch a web page and return compact readable text";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of(
                                "type", "string",
                                "description", "Target URL, e.g. https://example.com"
                        ),
                        "maxChars", Map.of(
                                "type", "integer",
                                "description", "Maximum returned character count, default 2000"
                        )
                ),
                "required", List.of("url")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String url = String.valueOf(arguments.getOrDefault("url", "")).trim();
            if (url.isBlank()) {
                return "网页抓取失败：url 不能为空";
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }

            int maxChars = parseMaxChars(arguments.get("maxChars"));

            String html = fetchBody(url);
            boolean usedReadableMirror = false;
            String mirrorWarning = "";

            // 许多站点首屏依赖 JS 渲染，直抓 HTML 很可能只有壳；此时回退到可读镜像。
            if (isLikelyJsShell(html)) {
                try {
                    html = fetchBody("https://r.jina.ai/http://" + stripProtocol(url));
                    usedReadableMirror = true;
                } catch (Exception mirrorEx) {
                    // 证书或网络问题时不直接失败，继续返回直抓结果，避免体验“全失败”。
                    mirrorWarning = "\n提示: readable-mirror 不可用(" + safeMessage(mirrorEx) + ")，已回退 direct";
                }
            }

            if (html == null || html.isBlank()) {
                return "网页抓取失败：页面内容为空";
            }

            String text = html
                    .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                    .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                    .replaceAll("(?is)<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (text.isEmpty()) {
                return "网页抓取失败：文本提取为空";
            }

            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "...";
            }

            return "网页抓取结果\nURL: " + url
                    + (usedReadableMirror ? "\n来源: readable-mirror" : "\n来源: direct")
                    + mirrorWarning
                    + "\n内容: " + text;
        } catch (Exception e) {
            return "网页抓取失败: " + (e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    private String fetchBody(String url) {
        return restClient.get()
                .uri(url)
                .accept(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN)
                .retrieve()
                .body(String.class);
    }

    private boolean isLikelyJsShell(String html) {
        if (html == null || html.isBlank()) {
            return true;
        }
        String lowered = html.toLowerCase();
        return lowered.contains("enable javascript")
                || lowered.contains("requires javascript")
                || lowered.contains("<noscript")
                || compactText(html).length() < 120;
    }

    private String stripProtocol(String url) {
        return url.replaceFirst("^https?://", "");
    }

    private String compactText(String html) {
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    private int parseMaxChars(Object value) {
        if (value == null) {
            return 2000;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return Math.max(300, Math.min(parsed, 10000));
        } catch (Exception ignored) {
            return 2000;
        }
    }
}

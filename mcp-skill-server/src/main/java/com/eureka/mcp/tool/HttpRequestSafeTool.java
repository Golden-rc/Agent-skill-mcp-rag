package com.eureka.mcp.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class HttpRequestSafeTool implements McpTool {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "http_request_safe";
    }

    @Override
    public String description() {
        return "Execute safe outbound HTTP request with host guard";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "Target URL"),
                        "method", Map.of("type", "string", "description", "GET|POST|PUT|PATCH|DELETE"),
                        "headers", Map.of("type", "object", "description", "Optional header map"),
                        "body", Map.of("type", "string", "description", "Optional request body"),
                        "maxChars", Map.of("type", "integer", "description", "Max response chars, default 3000")
                ),
                "required", List.of("url")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String rawUrl = String.valueOf(arguments.getOrDefault("url", "")).trim();
            if (rawUrl.isBlank()) {
                return "HTTP 请求失败: url 不能为空";
            }
            String url = rawUrl.startsWith("http://") || rawUrl.startsWith("https://") ? rawUrl : "https://" + rawUrl;
            URI uri = URI.create(url);
            validateHost(uri.getHost());

            String method = String.valueOf(arguments.getOrDefault("method", "GET")).trim().toUpperCase(Locale.ROOT);
            if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                return "HTTP 请求失败: 不支持 method=" + method;
            }

            int maxChars = parseMaxChars(arguments.get("maxChars"));
            String body = String.valueOf(arguments.getOrDefault("body", ""));
            Map<String, String> headers = parseHeaders(arguments.get("headers"));

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
            headers.forEach(builder::header);
            HttpRequest request = switch (method) {
                case "POST", "PUT", "PATCH" -> builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
                case "DELETE" -> builder.DELETE().build();
                default -> builder.GET().build();
            };

            long started = System.nanoTime();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - started) / 1_000_000L;
            String responseBody = response.body() == null ? "" : response.body();
            if (responseBody.length() > maxChars) {
                responseBody = responseBody.substring(0, maxChars) + "...";
            }
            return "HTTP 请求结果\nURL: " + url
                    + "\nMethod: " + method
                    + "\nStatus: " + response.statusCode()
                    + "\nLatencyMs: " + latency
                    + "\nBody: " + responseBody;
        } catch (Exception e) {
            return "HTTP 请求失败: " + safeMessage(e);
        }
    }

    private Map<String, String> parseHeaders(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, String> out = new HashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
                return out;
            }
            return objectMapper.readValue(String.valueOf(value), new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void validateHost(String host) throws Exception {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host 不能为空");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".local")) {
            throw new IllegalArgumentException("禁止访问本地地址");
        }
        InetAddress addr = InetAddress.getByName(host);
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            throw new IllegalArgumentException("禁止访问内网地址");
        }
    }

    private int parseMaxChars(Object value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return Math.max(300, Math.min(parsed, 20000));
        } catch (Exception ignored) {
            return 3000;
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "unknown" : message.replaceAll("\\s+", " ").trim();
    }
}

package com.eureka.mcp.tool;

import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;

@Component
public class UrlHealthCheckTool implements McpTool {

    @Override
    public String name() {
        return "url_health_check";
    }

    @Override
    public String description() {
        return "Check URL status, latency and TLS certificate brief";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "URL to check")
                ),
                "required", List.of("url")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String raw = String.valueOf(arguments.getOrDefault("url", "")).trim();
        if (raw.isBlank()) {
            return "URL 健康检查失败: url 不能为空";
        }
        String url = raw.startsWith("http://") || raw.startsWith("https://") ? raw : "https://" + raw;
        HttpURLConnection conn = null;
        try {
            long started = System.nanoTime();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.connect();
            long latency = (System.nanoTime() - started) / 1_000_000L;

            int status = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            String contentType = conn.getContentType();
            StringBuilder out = new StringBuilder();
            out.append("URL 健康检查结果\n")
                    .append("URL: ").append(url)
                    .append("\nHTTP: ").append(status)
                    .append("\nLatencyMs: ").append(latency)
                    .append("\nContentType: ").append(contentType == null ? "(unknown)" : contentType);
            if (location != null && !location.isBlank()) {
                out.append("\nRedirectTo: ").append(location);
            }

            if (conn instanceof HttpsURLConnection https) {
                try {
                    Certificate[] certs = https.getServerCertificates();
                    if (certs != null && certs.length > 0) {
                        out.append("\nTLS: ok")
                                .append("\nCertType: ").append(certs[0].getType())
                                .append("\nCertCount: ").append(certs.length);
                    }
                } catch (Exception certEx) {
                    out.append("\nTLS: warn (").append(safeMessage(certEx)).append(")");
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "URL 健康检查失败: " + safeMessage(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "unknown" : message.replaceAll("\\s+", " ").trim();
    }
}

package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
/**
 * MCP Client。
 * <p>
 * 通过 JSON-RPC 风格请求调用外部 MCP Skill Server 的工具。
 */
public class McpClientService {

    private final RestClient restClient;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public McpClientService(AppProperties appProperties) {
        this.restClient = RestClient.builder()
                .baseUrl(appProperties.getMcp().getBaseUrl())
                .build();
    }

    public String callTool(String toolName, String text) {
        ensureInitialized();

        // 组装工具参数。
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("text", text);

        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);

        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", "chat-tool-call");
        request.put("method", "tools/call");
        request.put("params", params);

        JsonNode response = invokeRpc(request);

        JsonNode result = response == null ? null : response.path("result").path("content");
        if (result == null || result.isMissingNode()) {
            // 工具异常时返回空字符串，避免主链路直接失败。
            return "";
        }

        // 标准 MCP tools/call 返回 content block 数组。
        if (result.isArray() && !result.isEmpty()) {
            JsonNode first = result.get(0);
            JsonNode textNode = first.path("text");
            if (!textNode.isMissingNode() && textNode.isTextual()) {
                return textNode.asText();
            }
        }

        // 兼容旧格式（content 直接为字符串）。
        return result.asText("");
    }

    public List<Map<String, Object>> listRegistryTools() {
        Map<String, Object> request = rpc("registry-list", "registry/tools/list", Map.of());
        JsonNode response = invokeRpc(request);
        JsonNode tools = response == null ? null : response.path("result").path("tools");
        return asMapList(tools);
    }

    public Map<String, Object> previewImport(String remoteBaseUrl) {
        Map<String, Object> request = rpc("registry-import-preview", "registry/tools/import.preview",
                Map.of("remoteBaseUrl", remoteBaseUrl));
        JsonNode response = invokeRpc(request);
        return asMap(response.path("result"));
    }

    public Map<String, Object> commitImport(String remoteBaseUrl, String alias, List<String> toolNames) {
        Map<String, Object> request = rpc("registry-import-commit", "registry/tools/import.commit",
                Map.of(
                        "remoteBaseUrl", remoteBaseUrl,
                        "alias", alias,
                        "toolNames", toolNames == null ? List.of() : toolNames
                ));
        JsonNode response = invokeRpc(request);
        return asMap(response.path("result"));
    }

    public Map<String, Object> exportManifest(List<String> toolNames) {
        Map<String, Object> request = rpc("registry-export", "registry/tools/export",
                Map.of("toolNames", toolNames == null ? List.of() : toolNames));
        JsonNode response = invokeRpc(request);
        return asMap(response.path("result"));
    }

    public Map<String, Object> toggleTool(String name, boolean enabled) {
        Map<String, Object> request = rpc("registry-toggle", "registry/tools/toggle",
                Map.of("name", name, "enabled", enabled));
        JsonNode response = invokeRpc(request);
        return asMap(response.path("result"));
    }

    public Map<String, Object> deleteTool(String name) {
        Map<String, Object> request = rpc("registry-delete", "registry/tools/delete", Map.of("name", name));
        JsonNode response = invokeRpc(request);
        return asMap(response.path("result"));
    }

    /**
     * 按标准 MCP 流程先 initialize，再发送 notifications/initialized。
     */
    private void ensureInitialized() {
        if (initialized.get()) {
            return;
        }
        synchronized (initialized) {
            if (initialized.get()) {
                return;
            }

            Map<String, Object> initializeRequest = new HashMap<>();
            initializeRequest.put("jsonrpc", "2.0");
            initializeRequest.put("id", "mcp-init");
            initializeRequest.put("method", "initialize");
            initializeRequest.put("params", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "agent-hub-client", "version", "1.0.0")
            ));

            restClient.post()
                    .uri("/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(initializeRequest)
                    .retrieve()
                    .body(JsonNode.class);

            Map<String, Object> initializedNotification = new HashMap<>();
            initializedNotification.put("jsonrpc", "2.0");
            initializedNotification.put("method", "notifications/initialized");
            initializedNotification.put("params", Map.of());

            restClient.post()
                    .uri("/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(initializedNotification)
                    .retrieve()
                    .toBodilessEntity();

            initialized.set(true);
        }
    }

    private JsonNode invokeRpc(Map<String, Object> request) {
        return restClient.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }

    private Map<String, Object> rpc(String id, String method, Map<String, Object> params) {
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> output = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (JsonNode item : node) {
            output.add(mapper.convertValue(item, Map.class));
        }
        return output;
    }
}

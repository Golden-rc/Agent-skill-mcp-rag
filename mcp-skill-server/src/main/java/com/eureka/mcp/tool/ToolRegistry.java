package com.eureka.mcp.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心。
 * <p>
 * 负责 builtin 工具管理，以及外部 MCP 工具导入/导出/代理调用。
 */
@Component
public class ToolRegistry {

    private final Map<String, McpTool> builtinToolsByName;
    private final Map<String, ImportedTool> importedToolsByName;
    private final ObjectMapper objectMapper;
    private final Path storePath;
    private final Set<String> initializedRemoteBaseUrls = ConcurrentHashMap.newKeySet();

    public ToolRegistry(List<McpTool> tools) {
        this.builtinToolsByName = new LinkedHashMap<>();
        for (McpTool tool : tools) {
            builtinToolsByName.put(tool.name(), tool);
        }

        this.importedToolsByName = new LinkedHashMap<>();
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.storePath = Paths.get("data", "tools-registry.json");
        loadImportedTools();
    }

    public synchronized List<Map<String, Object>> listToolMetadata() {
        List<Map<String, Object>> all = new ArrayList<>();

        for (McpTool tool : builtinToolsByName.values()) {
            all.add(toMetadata(tool.name(), tool.description(), tool.inputSchema()));
        }

        for (ImportedTool tool : importedToolsByName.values()) {
            if (!tool.isEnabled()) {
                continue;
            }
            all.add(toMetadata(tool.getName(), tool.getDescription(),
                    tool.getInputSchema() == null || tool.getInputSchema().isEmpty()
                            ? defaultTextSchema()
                            : tool.getInputSchema()));
        }

        return all;
    }

    public synchronized List<Map<String, Object>> listRegistryItems() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (McpTool tool : builtinToolsByName.values()) {
            items.add(Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "source", "builtin",
                    "enabled", true,
                    "inputSchema", tool.inputSchema()
            ));
        }
        for (ImportedTool tool : importedToolsByName.values()) {
            items.add(Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription(),
                    "source", "imported",
                    "enabled", tool.isEnabled(),
                    "remoteBaseUrl", tool.getRemoteBaseUrl(),
                    "remoteToolName", tool.getRemoteToolName(),
                    "inputSchema", tool.getInputSchema() == null ? defaultTextSchema() : tool.getInputSchema()
            ));
        }
        return items;
    }

    public synchronized Map<String, Object> exportManifest(List<String> names) {
        Set<String> filter = names == null ? Set.of() : Set.copyOf(names);
        List<Map<String, Object>> tools = listRegistryItems().stream()
                .filter(item -> filter.isEmpty() || filter.contains(String.valueOf(item.get("name"))))
                .toList();

        return Map.of(
                "format", "agent-hub-mcp-tool-manifest",
                "version", "1.0.0",
                "exportedAt", Instant.now().toString(),
                "count", tools.size(),
                "tools", tools
        );
    }

    public synchronized int deleteImportedTool(String name) {
        ImportedTool removed = importedToolsByName.remove(name);
        if (removed != null) {
            persistImportedTools();
            return 1;
        }
        return 0;
    }

    public synchronized int toggleImportedTool(String name, boolean enabled) {
        ImportedTool tool = importedToolsByName.get(name);
        if (tool == null) {
            return 0;
        }
        tool.setEnabled(enabled);
        persistImportedTools();
        return 1;
    }

    public synchronized String execute(String toolName, Map<String, Object> arguments) {
        McpTool builtin = builtinToolsByName.get(toolName);
        if (builtin != null) {
            return builtin.execute(arguments);
        }

        ImportedTool imported = importedToolsByName.get(toolName);
        if (imported == null || !imported.isEnabled()) {
            throw new IllegalArgumentException("tool not found: " + toolName);
        }
        return proxyCall(imported, arguments);
    }

    private String proxyCall(ImportedTool imported, Map<String, Object> arguments) {
        ensureRemoteInitialized(imported.getRemoteBaseUrl());

        Map<String, Object> params = new HashMap<>();
        params.put("name", imported.getRemoteToolName());
        params.put("arguments", arguments);

        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", "proxy-tool-call");
        request.put("method", "tools/call");
        request.put("params", params);

        RestClient restClient = RestClient.builder().baseUrl(imported.getRemoteBaseUrl()).build();
        Map<String, Object> response = restClient.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            return "";
        }
        Object resultObj = response.get("result");
        if (!(resultObj instanceof Map<?, ?> result)) {
            return "";
        }

        Object contentObj = result.get("content");
        if (contentObj instanceof List<?> blocks && !blocks.isEmpty()) {
            Object first = blocks.get(0);
            if (first instanceof Map<?, ?> map) {
                Object text = map.get("text");
                return text == null ? "" : String.valueOf(text);
            }
        }
        if (contentObj != null) {
            return String.valueOf(contentObj);
        }
        return "";
    }

    private void ensureRemoteInitialized(String remoteBaseUrl) {
        if (initializedRemoteBaseUrls.contains(remoteBaseUrl)) {
            return;
        }

        RestClient restClient = RestClient.builder().baseUrl(remoteBaseUrl).build();

        Map<String, Object> initializeRequest = new HashMap<>();
        initializeRequest.put("jsonrpc", "2.0");
        initializeRequest.put("id", "registry-init");
        initializeRequest.put("method", "initialize");
        initializeRequest.put("params", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agent-hub-tool-registry", "version", "1.0.0")
        ));

        restClient.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(initializeRequest)
                .retrieve()
                .body(Map.class);

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

        initializedRemoteBaseUrls.add(remoteBaseUrl);
    }

    private Map<String, Object> toMetadata(String name, String description, Map<String, Object> inputSchema) {
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", inputSchema
        );
    }

    private Map<String, Object> defaultTextSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of(
                                "type", "string",
                                "description", "Input text for tool processing"
                        )
                ),
                "required", List.of("text")
        );
    }

    private void loadImportedTools() {
        try {
            Files.createDirectories(storePath.getParent());
            if (!Files.exists(storePath)) {
                return;
            }

            List<ImportedTool> imported = objectMapper.readValue(
                    storePath.toFile(),
                    new TypeReference<List<ImportedTool>>() {
                    }
            );

            for (ImportedTool tool : imported) {
                importedToolsByName.put(tool.getName(), tool);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load imported tools", e);
        }
    }

    private void persistImportedTools() {
        try {
            Files.createDirectories(storePath.getParent());
            objectMapper.writeValue(storePath.toFile(), new ArrayList<>(importedToolsByName.values()));
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist imported tools", e);
        }
    }
}

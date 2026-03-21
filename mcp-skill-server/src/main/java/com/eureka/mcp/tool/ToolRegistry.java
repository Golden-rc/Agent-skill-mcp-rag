package com.eureka.mcp.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
            all.add(toMetadata(tool.name(), tool.description(), defaultTextSchema()));
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
                    "inputSchema", defaultTextSchema()
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

    public synchronized Map<String, Object> previewImport(String remoteBaseUrl) {
        ensureRemoteInitialized(remoteBaseUrl);
        List<Map<String, Object>> tools = fetchRemoteTools(remoteBaseUrl);
        return Map.of(
                "remoteBaseUrl", remoteBaseUrl,
                "tools", tools,
                "count", tools.size()
        );
    }

    public synchronized Map<String, Object> commitImport(String remoteBaseUrl,
                                                         String alias,
                                                         List<String> selectedRemoteToolNames) {
        ensureRemoteInitialized(remoteBaseUrl);
        String safeAlias = normalizeAlias(alias);
        List<Map<String, Object>> remoteTools = fetchRemoteTools(remoteBaseUrl);

        Set<String> selected = Set.copyOf(selectedRemoteToolNames == null ? List.of() : selectedRemoteToolNames);
        int imported = 0;

        for (Map<String, Object> remoteTool : remoteTools) {
            String remoteName = String.valueOf(remoteTool.get("name"));
            if (!selected.isEmpty() && !selected.contains(remoteName)) {
                continue;
            }

            String localName = uniqueLocalName(safeAlias + "." + remoteName);
            ImportedTool importedTool = new ImportedTool();
            importedTool.setName(localName);
            importedTool.setRemoteToolName(remoteName);
            importedTool.setDescription(String.valueOf(remoteTool.getOrDefault("description", "")));
            importedTool.setRemoteBaseUrl(remoteBaseUrl);
            importedTool.setEnabled(true);

            Object schema = remoteTool.get("inputSchema");
            if (schema instanceof Map<?, ?> m) {
                importedTool.setInputSchema((Map<String, Object>) m);
            } else {
                importedTool.setInputSchema(defaultTextSchema());
            }

            importedToolsByName.put(localName, importedTool);
            imported++;
        }

        persistImportedTools();
        return Map.of("imported", imported, "total", importedToolsByName.size());
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

    private List<Map<String, Object>> fetchRemoteTools(String remoteBaseUrl) {
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", "remote-tools-list");
        request.put("method", "tools/list");
        request.put("params", Map.of());

        RestClient restClient = RestClient.builder().baseUrl(remoteBaseUrl).build();
        Map<String, Object> response = restClient.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            return List.of();
        }

        Object resultObj = response.get("result");
        if (!(resultObj instanceof Map<?, ?> result)) {
            return List.of();
        }

        Object toolsObj = result.get("tools");
        if (!(toolsObj instanceof List<?> tools)) {
            return List.of();
        }

        List<Map<String, Object>> output = new ArrayList<>();
        for (Object tool : tools) {
            if (tool instanceof Map<?, ?> t) {
                output.add((Map<String, Object>) t);
            }
        }
        return output;
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

    private String normalizeAlias(String alias) {
        String safe = StringUtils.hasText(alias) ? alias.trim().toLowerCase() : "imported";
        return safe.replaceAll("[^a-z0-9._-]", "-");
    }

    private String uniqueLocalName(String candidate) {
        if (!builtinToolsByName.containsKey(candidate) && !importedToolsByName.containsKey(candidate)) {
            return candidate;
        }
        int i = 2;
        while (true) {
            String next = candidate + "-" + i;
            if (!builtinToolsByName.containsKey(next) && !importedToolsByName.containsKey(next)) {
                return next;
            }
            i++;
        }
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

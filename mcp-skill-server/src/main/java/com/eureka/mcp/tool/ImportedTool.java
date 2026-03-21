package com.eureka.mcp.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * 从外部 MCP 导入到本地 registry 的工具定义。
 */
public class ImportedTool {

    private String name;
    private String remoteToolName;
    private String description;
    private Map<String, Object> inputSchema = new HashMap<>();
    private String remoteBaseUrl;
    private boolean enabled = true;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemoteToolName() {
        return remoteToolName;
    }

    public void setRemoteToolName(String remoteToolName) {
        this.remoteToolName = remoteToolName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getRemoteBaseUrl() {
        return remoteBaseUrl;
    }

    public void setRemoteBaseUrl(String remoteBaseUrl) {
        this.remoteBaseUrl = remoteBaseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

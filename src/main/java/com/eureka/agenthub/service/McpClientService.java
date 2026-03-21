package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Service
/**
 * MCP Client。
 * <p>
 * 通过 JSON-RPC 风格请求调用外部 MCP Skill Server 的工具。
 */
public class McpClientService {

    private final RestClient restClient;

    public McpClientService(AppProperties appProperties) {
        this.restClient = RestClient.builder()
                .baseUrl(appProperties.getMcp().getBaseUrl())
                .build();
    }

    public String callTool(String toolName, String text) {
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

        // 按 MCP 协议发送 tools/call。
        JsonNode response = restClient.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        JsonNode result = response == null ? null : response.path("result").path("content");
        if (result == null || result.isMissingNode()) {
            // 工具异常时返回空字符串，避免主链路直接失败。
            return "";
        }
        return result.asText("");
    }
}

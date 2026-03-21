package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Service
public class McpClientService {

    private final RestClient restClient;

    public McpClientService(AppProperties appProperties) {
        this.restClient = RestClient.builder()
                .baseUrl(appProperties.getMcp().getBaseUrl())
                .build();
    }

    public String callTool(String toolName, String text) {
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

        JsonNode response = restClient.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        JsonNode result = response == null ? null : response.path("result").path("content");
        if (result == null || result.isMissingNode()) {
            return "";
        }
        return result.asText("");
    }
}

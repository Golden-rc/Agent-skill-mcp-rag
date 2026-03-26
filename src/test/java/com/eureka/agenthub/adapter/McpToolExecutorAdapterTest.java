package com.eureka.agenthub.adapter;

import com.eureka.agenthub.model.ToolDefinition;
import com.eureka.agenthub.service.McpClientService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolExecutorAdapterTest {

    @Test
    void shouldDelegateToolCallsAndListing() {
        McpClientService mcpClientService = mock(McpClientService.class);
        when(mcpClientService.listCallableTools()).thenReturn(List.of(
                new ToolDefinition("summarize", "desc", Map.of("type", "object"))
        ));
        when(mcpClientService.callTool("summarize", Map.of("text", "abc"))).thenReturn("ok");

        McpToolExecutorAdapter adapter = new McpToolExecutorAdapter(mcpClientService);
        List<ToolDefinition> tools = adapter.listCallableTools();
        String output = adapter.callTool("summarize", Map.of("text", "abc"));

        assertEquals(1, tools.size());
        assertEquals("ok", output);
        verify(mcpClientService).listCallableTools();
        verify(mcpClientService).callTool("summarize", Map.of("text", "abc"));
    }
}

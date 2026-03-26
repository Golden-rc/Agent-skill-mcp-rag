package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ToolCallRequest;
import com.eureka.agenthub.model.ToolChatResult;
import com.eureka.agenthub.model.ToolDefinition;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.port.ToolExecutorPort;
import com.eureka.agenthub.service.ModelClientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentChatOrchestratorTest {

    @Mock
    private MemoryPort memoryPort;
    @Mock
    private ToolExecutorPort toolExecutorPort;
    @Mock
    private ModelClientService modelClientService;

    @Test
    void shouldUseToolProtocolInAgentModeForOpenAi() {
        AppProperties properties = new AppProperties();
        properties.getChat().setToolCallingEnabled(true);

        AgentChatOrchestrator orchestrator = new AgentChatOrchestrator(memoryPort, toolExecutorPort, modelClientService, properties);

        ChatRequest request = new ChatRequest();
        request.setSessionId("agent-s1");
        request.setMessage("帮我看看上海天气");

        when(memoryPort.loadHistory("agent-s1")).thenReturn(List.of(new ChatMessage("user", "hello")));
        when(toolExecutorPort.listCallableTools()).thenReturn(List.of(
                new ToolDefinition("query_weather", "weather", Map.of("type", "object"))
        ));
        when(modelClientService.chatWithTools(eq("openai"), any(), any())).thenReturn(
                new ToolChatResult("", List.of(new ToolCallRequest("c1", "query_weather", Map.of("city", "上海")))),
                new ToolChatResult("上海当前多云，23°C", List.of())
        );
        when(toolExecutorPort.callTool(eq("query_weather"), any())).thenReturn("天气查询结果\n城市: 上海\n温度: 23°C");

        var resp = orchestrator.chat(request, "openai");

        assertTrue(resp.toolProtocolUsed());
        assertTrue(resp.toolCalls().contains("query_weather"));
        assertEquals("上海当前多云，23°C", resp.answer());
        verify(memoryPort, times(2)).append(eq("agent-s1"), any(ChatMessage.class));
    }
}

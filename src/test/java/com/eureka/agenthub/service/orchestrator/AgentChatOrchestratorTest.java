package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.service.ModelClientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
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
    private ModelClientService modelClientService;
    @Mock
    private AgentLangChainService agentLangChainService;

    @Test
    void shouldUseToolProtocolInAgentModeForOpenAi() {
        AppProperties properties = new AppProperties();
        properties.getChat().setToolCallingEnabled(true);

        AgentChatOrchestrator orchestrator = new AgentChatOrchestrator(memoryPort, modelClientService, properties, agentLangChainService);

        ChatRequest request = new ChatRequest();
        request.setSessionId("agent-s1");
        request.setMessage("帮我看看上海天气");

        when(memoryPort.loadHistory("agent-s1")).thenReturn(List.of(new ChatMessage("user", "hello")));
        when(agentLangChainService.run(eq("openai"), any(), eq("帮我看看上海天气"))).thenReturn(
                new AgentLangChainService.AgentRunResult(
                        "上海当前多云，23°C",
                        List.of("query_weather"),
                        List.of(),
                        List.of(),
                        1200,
                        1,
                        true
                )
        );

        var resp = orchestrator.chat(request, "openai");

        assertTrue(resp.toolProtocolUsed());
        assertTrue(resp.toolCalls().contains("query_weather"));
        assertEquals("上海当前多云，23°C", resp.answer());
        verify(memoryPort, times(2)).append(eq("agent-s1"), any(ChatMessage.class));
    }
}

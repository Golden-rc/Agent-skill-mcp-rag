package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.SseEvent;
import com.eureka.agenthub.port.MemoryPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgentChatOrchestratorTest {

    @Test
    void shouldUseToolProtocolInAgentModeForOpenAi() {
        MemoryPort memoryPort = mock(MemoryPort.class);
        AgentLangChainService agentLangChainService = new AgentLangChainService(null, null) {
            @Override
            public AgentRunResult run(String provider,
                                      List<ChatMessage> history,
                                      String userInput,
                                      ToolPolicy toolPolicy) {
                return new AgentRunResult(
                        "上海当前多云，23°C",
                        List.of("query_weather"),
                        List.of(),
                        List.of(),
                        1200,
                        1,
                        true,
                        "上海当前多云，23°C"
                );
            }
        };
        AppProperties properties = new AppProperties();
        properties.getChat().setToolCallingEnabled(true);

        AgentChatOrchestrator orchestrator = new AgentChatOrchestrator(memoryPort, null, properties, agentLangChainService);

        ChatRequest request = new ChatRequest();
        request.setSessionId("agent-s1");
        request.setMessage("帮我看看上海天气");

        org.mockito.Mockito.when(memoryPort.loadHistory("agent-s1")).thenReturn(List.of(new ChatMessage("user", "hello")));

        var resp = orchestrator.chat(request, "openai");

        assertTrue(resp.toolProtocolUsed());
        assertTrue(resp.toolCalls().contains("query_weather"));
        assertEquals("上海当前多云，23°C", resp.answer());
        verify(memoryPort, times(2)).append(eq("agent-s1"), any(ChatMessage.class));
    }

    @Test
    void shouldFallbackToToolOutputWhenModelUsesFailureStyleText() {
        MemoryPort memoryPort = mock(MemoryPort.class);
        AgentLangChainService agentLangChainService = new AgentLangChainService(null, null) {
            @Override
            public AgentRunResult run(String provider,
                                      List<ChatMessage> history,
                                      String userInput,
                                      ToolPolicy toolPolicy) {
                return new AgentRunResult(
                        "抱歉，翻译功能目前遇到了技术问题。",
                        List.of("translate_text"),
                        List.of(),
                        List.of(),
                        900,
                        1,
                        true,
                        "翻译结果\n结果: 这是一只猫"
                );
            }
        };
        AppProperties properties = new AppProperties();
        properties.getChat().setToolCallingEnabled(true);

        AgentChatOrchestrator orchestrator = new AgentChatOrchestrator(memoryPort, null, properties, agentLangChainService);

        ChatRequest request = new ChatRequest();
        request.setSessionId("agent-s2");
        request.setMessage("翻译：this is a cat");

        org.mockito.Mockito.when(memoryPort.loadHistory("agent-s2")).thenReturn(List.of());

        var resp = orchestrator.chat(request, "openai");

        assertEquals("翻译结果\n结果: 这是一只猫", resp.answer());
        assertTrue(resp.toolErrors().isEmpty());
    }

    @Test
    void shouldPassToolPolicyFromRequest() {
        MemoryPort memoryPort = mock(MemoryPort.class);
        AgentLangChainService agentLangChainService = new AgentLangChainService(null, null) {
            @Override
            public AgentRunResult run(String provider,
                                      List<ChatMessage> history,
                                      String userInput,
                                      ToolPolicy toolPolicy) {
                assertEquals(Set.of("translate_text"), toolPolicy.allowedTools());
                assertTrue(!toolPolicy.internetEnabled());
                return new AgentRunResult(
                        "ok",
                        List.of(),
                        List.of(),
                        List.of(),
                        10,
                        0,
                        true,
                        ""
                );
            }
        };
        AppProperties properties = new AppProperties();
        properties.getChat().setToolCallingEnabled(true);

        AgentChatOrchestrator orchestrator = new AgentChatOrchestrator(memoryPort, null, properties, agentLangChainService);

        ChatRequest request = new ChatRequest();
        request.setSessionId("agent-s3");
        request.setMessage("测试策略");
        request.setAllowedTools(List.of("translate_text", " "));
        request.setInternetEnabled(false);

        org.mockito.Mockito.when(memoryPort.loadHistory("agent-s3")).thenReturn(List.of());

        var resp = orchestrator.chat(request, "openai");
        assertEquals("ok", resp.answer());
    }

    @Test
    void shouldEmitSseEventsForAgentStreamPath() {
        MemoryPort memoryPort = mock(MemoryPort.class);
        AgentLangChainService agentLangChainService = new AgentLangChainService(null, null) {
            @Override
            public AgentRunResult run(String provider,
                                      List<ChatMessage> history,
                                      String userInput,
                                      ToolPolicy toolPolicy,
                                      Consumer<SseEvent> emitter) {
                emitter.accept(SseEvent.toolStart("translate_text"));
                emitter.accept(SseEvent.toolDone("translate_text", "结果: 这是一只猫"));
                emitter.accept(SseEvent.token("这是一只猫"));
                return new AgentRunResult(
                        "这是一只猫",
                        List.of("translate_text"),
                        List.of(),
                        List.of(),
                        100,
                        1,
                        true,
                        "这是一只猫"
                );
            }
        };
        AppProperties properties = new AppProperties();
        properties.getChat().setToolCallingEnabled(true);

        AgentChatOrchestrator orchestrator = new AgentChatOrchestrator(memoryPort, null, properties, agentLangChainService);

        ChatRequest request = new ChatRequest();
        request.setSessionId("agent-stream-1");
        request.setMessage("翻译 this is a cat");
        request.setAllowedTools(List.of("translate_text"));
        request.setInternetEnabled(false);

        org.mockito.Mockito.when(memoryPort.loadHistory("agent-stream-1")).thenReturn(List.of());

        List<SseEvent> events = new ArrayList<>();
        orchestrator.stream(request, "openai", events::add);

        assertTrue(events.stream().anyMatch(e -> "status".equals(e.type())));
        assertTrue(events.stream().anyMatch(e -> "tool_start".equals(e.type())));
        assertTrue(events.stream().anyMatch(e -> "tool_done".equals(e.type())));
        assertTrue(events.stream().anyMatch(e -> "done".equals(e.type())));
    }
}

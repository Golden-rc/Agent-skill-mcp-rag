package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.RagHit;
import com.eureka.agenthub.model.ToolCallRequest;
import com.eureka.agenthub.model.ToolChatResult;
import com.eureka.agenthub.model.ToolDefinition;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.port.RetrieverPort;
import com.eureka.agenthub.port.ToolExecutorPort;
import com.eureka.agenthub.service.ModelClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassicChatOrchestratorTest {

    @Mock
    private MemoryPort memoryPort;
    @Mock
    private RetrieverPort retrieverPort;
    @Mock
    private ToolExecutorPort toolExecutorPort;
    @Mock
    private ModelClientService modelClientService;

    private AppProperties appProperties;
    private ClassicChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getChat().setToolCallingEnabled(false);
        appProperties.getMemory().setDirectHistoryMessages(2);
        orchestrator = new ClassicChatOrchestrator(memoryPort, retrieverPort, toolExecutorPort, modelClientService, appProperties);
    }

    @Test
    void shouldUseRecentHistoryInDirectMode() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s1");
        request.setMode("direct");
        request.setMessage("我上一个问题是什么");

        when(memoryPort.loadHistory("s1")).thenReturn(List.of(
                new ChatMessage("user", "q1"),
                new ChatMessage("assistant", "a1"),
                new ChatMessage("user", "q2"),
                new ChatMessage("assistant", "a2")
        ));
        when(modelClientService.chat(eq("ollama"), any())).thenReturn("ok");

        var response = orchestrator.chat(request, "ollama");

        assertEquals("ok", response.answer());
        verify(retrieverPort, never()).retrieve(any(), anyInt());
    }

    @Test
    void shouldReturnInsufficientEvidenceWhenNoHitsAndNoProtocol() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s2");
        request.setMode("rag");
        request.setMessage("给我数据库迁移步骤");

        when(memoryPort.loadHistory("s2")).thenReturn(List.of());
        when(retrieverPort.retrieve("给我数据库迁移步骤", 5)).thenReturn(List.of());

        var response = orchestrator.chat(request, "ollama");

        assertTrue(response.answer().contains("没有在知识库中找到足够依据"));
        verify(modelClientService, never()).chat(eq("ollama"), any());
    }

    @Test
    void shouldUseToolProtocolWhenOpenAi() {
        appProperties.getChat().setToolCallingEnabled(true);

        ChatRequest request = new ChatRequest();
        request.setSessionId("s3");
        request.setMode("rag");
        request.setMessage("请总结这段内容");

        when(memoryPort.loadHistory("s3")).thenReturn(List.of());
        when(retrieverPort.retrieve("请总结这段内容", 5)).thenReturn(List.of(new RagHit("kb", "chunk", 0.9)));
        when(toolExecutorPort.listCallableTools()).thenReturn(List.of(
                new ToolDefinition("summarize", "总结文本", Map.of("type", "object"))
        ));
        when(modelClientService.chatWithTools(eq("openai"), any(), any())).thenReturn(
                new ToolChatResult("", List.of(new ToolCallRequest("call-1", "summarize", Map.of("text", "abc")))),
                new ToolChatResult("总结完成", List.of())
        );
        when(toolExecutorPort.callTool(eq("summarize"), any())).thenReturn("摘要结果");

        var response = orchestrator.chat(request, "openai");

        assertTrue(response.toolProtocolUsed());
        assertEquals(2, response.toolRounds());
        assertTrue(response.toolCalls().contains("summarize"));
    }

    @Test
    void shouldSkipRetrievalWhenToolTestModeForOpenAi() {
        appProperties.getChat().setToolCallingEnabled(true);

        ChatRequest request = new ChatRequest();
        request.setSessionId("s5");
        request.setMode("rag");
        request.setToolTestMode(true);
        request.setMessage("帮我查看baidu.com");

        when(memoryPort.loadHistory("s5")).thenReturn(List.of());
        when(toolExecutorPort.listCallableTools()).thenReturn(List.of(
                new ToolDefinition("web_fetch", "抓取网页", Map.of("type", "object"))
        ));
        when(modelClientService.chatWithTools(eq("openai"), any(), any())).thenReturn(
                new ToolChatResult("网页摘要", List.of())
        );

        var response = orchestrator.chat(request, "openai");

        assertTrue(response.toolProtocolUsed());
        verify(retrieverPort, never()).retrieve(any(), anyInt());
    }

    @Test
    void shouldReturnToolModeErrorWhenTestModeAndNotOpenAi() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s4");
        request.setMode("direct");
        request.setToolTestMode(true);
        request.setMessage("测试协议工具");

        when(memoryPort.loadHistory("s4")).thenReturn(List.of());
        when(modelClientService.chat(eq("ollama"), any())).thenReturn("ok");

        var response = orchestrator.chat(request, "ollama");

        assertFalse(response.toolProtocolUsed());
        assertTrue(response.toolErrors().contains("tool test mode requires openai provider"));
    }
}

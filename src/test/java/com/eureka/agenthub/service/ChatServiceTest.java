package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.RagHit;
import com.eureka.agenthub.model.ToolCallRequest;
import com.eureka.agenthub.model.ToolChatResult;
import com.eureka.agenthub.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class ChatServiceTest {

    @Mock
    private ProviderRouter providerRouter;
    @Mock
    private MemoryService memoryService;
    @Mock
    private RagService ragService;
    @Mock
    private McpClientService mcpClientService;
    @Mock
    private ModelClientService modelClientService;

    private AppProperties appProperties;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getChat().setToolCallingEnabled(false);
        appProperties.getMemory().setDirectHistoryMessages(2);
        chatService = new ChatService(
                providerRouter,
                memoryService,
                ragService,
                mcpClientService,
                modelClientService,
                appProperties
        );
    }

    @Test
    void shouldUseRecentHistoryInDirectMode() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s1");
        request.setProvider("ollama");
        request.setMode("direct");
        request.setMessage("我上一个问题是什么");

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "q1"),
                new ChatMessage("assistant", "a1"),
                new ChatMessage("user", "q2"),
                new ChatMessage("assistant", "a2")
        );

        when(providerRouter.pickProvider("ollama")).thenReturn("ollama");
        when(memoryService.loadHistory("s1")).thenReturn(history);
        when(modelClientService.chat(eq("ollama"), any())).thenReturn("ok");

        chatService.chat(request);

        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClientService).chat(eq("ollama"), promptCaptor.capture());
        List<ChatMessage> prompt = promptCaptor.getValue();

        // system + 最近2条历史 + user
        assertEquals(4, prompt.size());
        assertEquals("q2", prompt.get(1).content());
        assertEquals("a2", prompt.get(2).content());
        assertTrue(prompt.get(3).content().contains("我上一个问题是什么"));
        verify(ragService, never()).retrieve(any(), anyInt());
    }

    @Test
    void shouldUseFullHistoryInRagMode() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s2");
        request.setProvider("ollama");
        request.setMode("rag");
        request.setMessage("根据历史继续");

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "q1"),
                new ChatMessage("assistant", "a1"),
                new ChatMessage("user", "q2"),
                new ChatMessage("assistant", "a2")
        );

        when(providerRouter.pickProvider("ollama")).thenReturn("ollama");
        when(memoryService.loadHistory("s2")).thenReturn(history);
        when(ragService.retrieve("根据历史继续", 5)).thenReturn(List.of(new RagHit("kb", "chunk", 0.9)));
        when(modelClientService.chat(eq("ollama"), any())).thenReturn("ok");

        chatService.chat(request);

        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClientService).chat(eq("ollama"), promptCaptor.capture());
        List<ChatMessage> prompt = promptCaptor.getValue();

        // system + 全量历史4条 + user
        assertEquals(6, prompt.size());
        assertEquals("q1", prompt.get(1).content());
        assertEquals("a2", prompt.get(4).content());
        verify(ragService).retrieve("根据历史继续", 5);
    }

    @Test
    void shouldReturnInsufficientEvidenceTemplateWhenRagHasNoHits() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s3");
        request.setProvider("ollama");
        request.setMode("rag");
        request.setMessage("给我数据库迁移步骤");

        when(providerRouter.pickProvider("ollama")).thenReturn("ollama");
        when(memoryService.loadHistory("s3")).thenReturn(List.of());
        when(ragService.retrieve("给我数据库迁移步骤", 5)).thenReturn(List.of());

        var response = chatService.chat(request);

        assertTrue(response.answer().contains("没有在知识库中找到足够依据"));
        verify(modelClientService, never()).chat(eq("ollama"), any());
    }

    @Test
    void shouldPreferRagForContextualFollowUpInAutoMode() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s4");
        request.setProvider("ollama");
        request.setMode("auto");
        request.setMessage("我上一个问题是啥");

        when(providerRouter.pickProvider("ollama")).thenReturn("ollama");
        when(memoryService.loadHistory("s4")).thenReturn(List.of(new ChatMessage("user", "前一个问题")));
        when(ragService.retrieve("我上一个问题是啥", 5)).thenReturn(List.of(new RagHit("mem", "前文有提问", 0.95)));
        when(modelClientService.chat(eq("ollama"), any())).thenReturn("你上一个问题是... ");

        chatService.chat(request);

        verify(ragService).retrieve("我上一个问题是啥", 5);
        verify(modelClientService, never()).classifyMode(any(), any());
    }

    @Test
    void shouldUseToolProtocolWhenOpenAiAndRagMode() {
        appProperties.getChat().setToolCallingEnabled(true);

        ChatRequest request = new ChatRequest();
        request.setSessionId("s5");
        request.setProvider("openai");
        request.setMode("rag");
        request.setMessage("请总结这段内容");

        when(providerRouter.pickProvider("openai")).thenReturn("openai");
        when(memoryService.loadHistory("s5")).thenReturn(List.of());
        when(ragService.retrieve("请总结这段内容", 5)).thenReturn(List.of(new RagHit("kb", "chunk", 0.91)));
        when(mcpClientService.listCallableTools()).thenReturn(List.of(
                new ToolDefinition("summarize", "总结文本", Map.of("type", "object"))
        ));
        when(modelClientService.chatWithTools(eq("openai"), any(), any())).thenReturn(
                new ToolChatResult("", List.of(new ToolCallRequest("call-1", "summarize", Map.of("text", "abc")))),
                new ToolChatResult("总结完成", List.of())
        );
        when(mcpClientService.callTool(eq("summarize"), org.mockito.ArgumentMatchers.<Map<String, Object>>any())).thenReturn("摘要结果");

        var response = chatService.chat(request);

        assertTrue(response.toolProtocolUsed());
        assertEquals(2, response.toolRounds());
        assertEquals("总结完成", response.answer());
        assertEquals("summarize", response.toolCalls().get(0));
        verify(modelClientService, never()).chat(eq("openai"), any());
    }

    @Test
    void shouldExposeToolProtocolErrorWhenToolTestModeWithoutOpenAi() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("s6");
        request.setProvider("ollama");
        request.setMode("direct");
        request.setToolTestMode(true);
        request.setMessage("测试协议工具");

        when(providerRouter.pickProvider("ollama")).thenReturn("ollama");
        when(memoryService.loadHistory("s6")).thenReturn(List.of());
        when(modelClientService.chat(eq("ollama"), any())).thenReturn("ok");

        var response = chatService.chat(request);

        assertTrue(response.toolErrors().contains("tool test mode requires openai provider"));
        assertFalse(response.toolProtocolUsed());
    }
}

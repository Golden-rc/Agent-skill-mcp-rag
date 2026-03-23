package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.RagHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

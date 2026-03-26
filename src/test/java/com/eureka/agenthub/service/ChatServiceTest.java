package com.eureka.agenthub.service;

import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.service.orchestrator.ChatOrchestrator;
import com.eureka.agenthub.service.orchestrator.OrchestratorRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ProviderRouter providerRouter;
    @Mock
    private OrchestratorRouter orchestratorRouter;
    @Mock
    private ChatOrchestrator chatOrchestrator;

    @Test
    void shouldRouteToSelectedOrchestrator() {
        ChatService chatService = new ChatService(providerRouter, orchestratorRouter);

        ChatRequest request = new ChatRequest();
        request.setSessionId("s1");
        request.setProvider("auto");
        request.setMessage("hello");

        ChatResponse base = new ChatResponse(
                "ok",
                "openai",
                List.of(),
                List.of(),
                List.of(),
                false,
                0,
                List.of(),
                List.of(),
                "",
                "",
                "rag",
                "manual-rag"
        );

        when(providerRouter.pickProvider("auto")).thenReturn("openai");
        when(orchestratorRouter.decide(request)).thenReturn(new OrchestratorRouter.Decision("classic", "config"));
        when(orchestratorRouter.get("classic")).thenReturn(chatOrchestrator);
        when(chatOrchestrator.chat(request, "openai")).thenReturn(base);

        ChatResponse result = chatService.chat(request);

        assertEquals("classic", result.orchestratorUsed());
        assertEquals("config", result.orchestratorReason());
        assertEquals("ok", result.answer());
        verify(chatOrchestrator).chat(request, "openai");
    }
}

package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.SseEvent;
import com.eureka.agenthub.service.orchestrator.ChatOrchestrator;
import com.eureka.agenthub.service.orchestrator.OrchestratorRouter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceTest {

    @Test
    void shouldRouteToSelectedOrchestrator() {
        AppProperties properties = new AppProperties();
        properties.getOpenai().setApiKey("test-key");
        properties.getChat().setAgentEnabled(true);
        properties.getChat().setOrchestrator("classic");

        ProviderRouter providerRouter = new ProviderRouter(properties);
        FakeOrchestrator orchestrator = new FakeOrchestrator("classic");
        OrchestratorRouter orchestratorRouter = new OrchestratorRouter(List.of(orchestrator), properties);
        ChatService chatService = new ChatService(providerRouter, orchestratorRouter);

        ChatRequest request = new ChatRequest();
        request.setSessionId("s1");
        request.setProvider("auto");
        request.setMessage("hello");

        ChatResponse result = chatService.chat(request);

        assertEquals("classic", result.orchestratorUsed());
        assertEquals("config", result.orchestratorReason());
        assertEquals("ok", result.answer());
        assertEquals("openai", result.providerUsed());
    }

    @Test
    void shouldAugmentDoneEventWithOrchestratorDecisionInStream() {
        AppProperties properties = new AppProperties();
        properties.getOpenai().setApiKey("test-key");
        properties.getChat().setAgentEnabled(true);
        properties.getChat().setOrchestrator("agent");

        ProviderRouter providerRouter = new ProviderRouter(properties);
        FakeOrchestrator orchestrator = new FakeOrchestrator("agent");
        OrchestratorRouter orchestratorRouter = new OrchestratorRouter(List.of(orchestrator), properties);
        ChatService chatService = new ChatService(providerRouter, orchestratorRouter);

        ChatRequest request = new ChatRequest();
        request.setSessionId("s-stream");
        request.setProvider("auto");
        request.setMessage("hello");

        List<SseEvent> events = new ArrayList<>();
        chatService.stream(request, events::add);

        assertEquals(2, events.size());
        assertEquals("status", events.get(0).type());
        assertEquals("done", events.get(1).type());
        assertTrue(events.get(1).data() != null);
        assertEquals("agent", events.get(1).data().orchestratorUsed());
        assertEquals("config", events.get(1).data().orchestratorReason());
    }

    private static class FakeOrchestrator implements ChatOrchestrator {
        private final String key;

        private FakeOrchestrator(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public ChatResponse chat(ChatRequest request, String provider) {
            return base(provider, key);
        }

        @Override
        public void stream(ChatRequest request, String provider, Consumer<SseEvent> emitter) {
            emitter.accept(SseEvent.status("streaming"));
            emitter.accept(SseEvent.done(base(provider, key)));
        }

        private ChatResponse base(String provider, String mode) {
            return new ChatResponse(
                    "ok",
                    provider,
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    0,
                    List.of(),
                    List.of(),
                    "",
                    "",
                    mode,
                    "manual"
            );
        }
    }
}

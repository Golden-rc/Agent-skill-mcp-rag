package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrchestratorRouterTest {

    @Test
    void shouldUseRequestOrchestratorFirst() {
        AppProperties properties = new AppProperties();
        properties.getChat().setOrchestrator("classic");
        properties.getChat().setAgentEnabled(true);

        OrchestratorRouter router = new OrchestratorRouter(
                List.of(new FakeOrchestrator("classic"), new FakeOrchestrator("agent")),
                properties
        );

        ChatRequest request = new ChatRequest();
        request.setOrchestrator("agent");
        OrchestratorRouter.Decision decision = router.decide(request);

        assertEquals("agent", decision.orchestratorUsed());
        assertEquals("request", decision.orchestratorReason());
    }

    @Test
    void shouldFallbackToClassicWhenAgentDisabled() {
        AppProperties properties = new AppProperties();
        properties.getChat().setOrchestrator("agent");
        properties.getChat().setAgentEnabled(false);

        OrchestratorRouter router = new OrchestratorRouter(
                List.of(new FakeOrchestrator("classic"), new FakeOrchestrator("agent")),
                properties
        );

        ChatRequest request = new ChatRequest();
        OrchestratorRouter.Decision decision = router.decide(request);

        assertEquals("classic", decision.orchestratorUsed());
        assertEquals("config-fallback-agent-disabled", decision.orchestratorReason());
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
            return new ChatResponse(
                    "ok", provider, List.of(), List.of(), List.of(),
                    false, 0, List.of(), List.of(),
                    "", "", "direct", "manual-direct"
            );
        }
    }
}

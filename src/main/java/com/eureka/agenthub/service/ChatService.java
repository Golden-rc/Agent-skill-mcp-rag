package com.eureka.agenthub.service;

import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.SseEvent;
import com.eureka.agenthub.service.orchestrator.ChatOrchestrator;
import com.eureka.agenthub.service.orchestrator.OrchestratorRouter;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 聊天入口服务（路由层）。
 * <p>
 * 仅负责 provider 与 orchestrator 决策，再分发到对应编排器执行。
 */
@Service
public class ChatService {

    private final ProviderRouter providerRouter;
    private final OrchestratorRouter orchestratorRouter;

    public ChatService(ProviderRouter providerRouter,
                       OrchestratorRouter orchestratorRouter) {
        this.providerRouter = providerRouter;
        this.orchestratorRouter = orchestratorRouter;
    }

    public ChatResponse chat(ChatRequest request) {
        String provider = providerRouter.pickProvider(request.getProvider());
        OrchestratorRouter.Decision decision = orchestratorRouter.decide(request);
        ChatOrchestrator orchestrator = orchestratorRouter.get(decision.orchestratorUsed());

        ChatResponse base = orchestrator.chat(request, provider);
        return new ChatResponse(
                base.answer(),
                base.providerUsed(),
                base.citations(),
                base.toolCalls(),
                base.imageUrls(),
                base.toolProtocolUsed(),
                base.toolRounds(),
                base.toolRoundLatenciesMs(),
                base.toolErrors(),
                decision.orchestratorUsed(),
                decision.orchestratorReason(),
                base.modeUsed(),
                base.modeReason()
        );
    }

    /**
     * SSE 流式版本。编排器推送的 done 事件会被拦截并补全 orchestrator 路由决策信息后再转发。
     */
    public void stream(ChatRequest request, Consumer<SseEvent> emitter) {
        String provider = providerRouter.pickProvider(request.getProvider());
        OrchestratorRouter.Decision decision = orchestratorRouter.decide(request);
        ChatOrchestrator orchestrator = orchestratorRouter.get(decision.orchestratorUsed());

        orchestrator.stream(request, provider, event -> {
            if ("done".equals(event.type()) && event.data() != null) {
                ChatResponse base = event.data();
                ChatResponse augmented = new ChatResponse(
                        base.answer(),
                        base.providerUsed(),
                        base.citations(),
                        base.toolCalls(),
                        base.imageUrls(),
                        base.toolProtocolUsed(),
                        base.toolRounds(),
                        base.toolRoundLatenciesMs(),
                        base.toolErrors(),
                        decision.orchestratorUsed(),
                        decision.orchestratorReason(),
                        base.modeUsed(),
                        base.modeReason()
                );
                emitter.accept(SseEvent.done(augmented));
            } else {
                emitter.accept(event);
            }
        });
    }
}

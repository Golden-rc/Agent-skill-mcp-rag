package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.service.ModelClientService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 编排最小骨架。
 * <p>
 * P4 阶段先保证“路径分离且可运行”，P5 再接 langchain4j/langgraph4j 完整图编排。
 */
@Component
public class AgentChatOrchestrator implements ChatOrchestrator {

    private static final String AGENT_SYSTEM_PROMPT =
            "You are an agent-style assistant. Keep answers concise and actionable. " +
                    "When context is insufficient, ask one clarifying question.";

    private final MemoryPort memoryPort;
    private final ModelClientService modelClientService;

    public AgentChatOrchestrator(MemoryPort memoryPort,
                                 ModelClientService modelClientService) {
        this.memoryPort = memoryPort;
        this.modelClientService = modelClientService;
    }

    @Override
    public String key() {
        return OrchestratorRouter.AGENT;
    }

    @Override
    public ChatResponse chat(ChatRequest request, String provider) {
        List<ChatMessage> history = memoryPort.loadHistory(request.getSessionId());

        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(new ChatMessage("system", AGENT_SYSTEM_PROMPT));
        prompt.addAll(history);
        prompt.add(new ChatMessage("user", request.getMessage()));

        String answer = modelClientService.chat(provider, prompt);

        memoryPort.append(request.getSessionId(), new ChatMessage("user", request.getMessage()));
        memoryPort.append(request.getSessionId(), new ChatMessage("assistant", answer));

        return new ChatResponse(
                answer,
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
                "agent",
                "agent-minimal"
        );
    }
}

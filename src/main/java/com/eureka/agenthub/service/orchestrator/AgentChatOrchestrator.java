package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.service.ModelClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AgentChatOrchestrator.class);

    private static final String AGENT_SYSTEM_PROMPT =
            "You are an agent-style assistant. Keep answers concise and actionable. " +
                    "When context is insufficient, ask one clarifying question.";

    private final MemoryPort memoryPort;
    private final ModelClientService modelClientService;
    private final AppProperties appProperties;
    private final AgentLangChainService agentLangChainService;

    public AgentChatOrchestrator(MemoryPort memoryPort,
                                 ModelClientService modelClientService,
                                 AppProperties appProperties,
                                 AgentLangChainService agentLangChainService) {
        this.memoryPort = memoryPort;
        this.modelClientService = modelClientService;
        this.appProperties = appProperties;
        this.agentLangChainService = agentLangChainService;
    }

    @Override
    public String key() {
        return OrchestratorRouter.AGENT;
    }

    @Override
    public ChatResponse chat(ChatRequest request, String provider) {
        List<ChatMessage> history = memoryPort.loadHistory(request.getSessionId());

        List<String> toolCalls = new ArrayList<>();
        List<String> toolErrors = new ArrayList<>();
        List<String> imageUrls = new ArrayList<>();
        List<Long> roundLatenciesMs = new ArrayList<>();

        boolean protocolEnabled = "openai".equals(provider) && appProperties.getChat().isToolCallingEnabled();
        String answer;
        int rounds = 0;
        if (protocolEnabled) {
            AgentLangChainService.AgentRunResult result = agentLangChainService.run(provider, history, request.getMessage());
            answer = result.answer();
            rounds = result.rounds();
            toolCalls.addAll(result.toolCalls());
            toolErrors.addAll(result.toolErrors());
            imageUrls.addAll(result.imageUrls());
            roundLatenciesMs.add(result.latencyMs());
        } else {
            List<ChatMessage> prompt = new ArrayList<>();
            prompt.add(new ChatMessage("system", AGENT_SYSTEM_PROMPT));
            prompt.addAll(history);
            prompt.add(new ChatMessage("user", request.getMessage()));
            answer = modelClientService.chat(provider, prompt);
        }

        memoryPort.append(request.getSessionId(), new ChatMessage("user", request.getMessage()));
        memoryPort.append(request.getSessionId(), new ChatMessage("assistant", answer));

        log.info("agent chat result session={} provider={} protocol={} rounds={} tools={} errors={}",
                request.getSessionId(), provider, protocolEnabled, rounds, toolCalls, toolErrors);

        return new ChatResponse(
                answer,
                provider,
                List.of(),
                toolCalls,
                imageUrls,
                protocolEnabled,
                rounds,
                roundLatenciesMs,
                toolErrors,
                "",
                "",
                "agent",
                protocolEnabled ? "agent-tool-protocol" : "agent-direct"
        );
    }
}

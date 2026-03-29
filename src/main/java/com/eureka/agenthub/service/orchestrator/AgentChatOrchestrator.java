package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.SseEvent;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.service.ModelClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Agent 编排器。
 * <p>
 * 当前通过 AgentLangChainService 接入 LangChain4j 工具调用链路，
 * 与 classic 编排路径保持互斥，便于灰度验证与回退。
 */
@Component
public class AgentChatOrchestrator implements ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentChatOrchestrator.class);

    private static final String AGENT_SYSTEM_PROMPT =
            "You are an agent-style assistant. Keep answers concise and actionable. " +
                    "When context is insufficient, ask one clarifying question.";
    private static final Pattern FAILURE_STYLE_PATTERN = Pattern.compile(
            "(技术问题|暂时无法|稍后再试|technical issue|temporarily unavailable|try again later)",
            Pattern.CASE_INSENSITIVE
    );

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
            // OpenAI 下使用 LangChain4j agent + MCP tools。
            AgentLangChainService.ToolPolicy toolPolicy = new AgentLangChainService.ToolPolicy(
                    normalizeAllowedTools(request.getAllowedTools()),
                    request.isInternetEnabled()
            );
            AgentLangChainService.AgentRunResult result = agentLangChainService.run(provider, history, request.getMessage(), toolPolicy);
            answer = normalizeFinalAnswer(result);
            rounds = result.rounds();
            toolCalls.addAll(result.toolCalls());
            toolErrors.addAll(result.toolErrors());
            imageUrls.addAll(result.imageUrls());
            roundLatenciesMs.add(result.latencyMs());
        } else {
            // 非 OpenAI 或关闭工具协议时，回退纯文本 agent 回答。
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

    @Override
    public void stream(ChatRequest request, String provider, Consumer<SseEvent> emitter) {
        List<ChatMessage> history = memoryPort.loadHistory(request.getSessionId());

        List<String> toolCalls = new ArrayList<>();
        List<String> toolErrors = new ArrayList<>();
        List<String> imageUrls = new ArrayList<>();
        List<Long> roundLatenciesMs = new ArrayList<>();

        boolean protocolEnabled = "openai".equals(provider) && appProperties.getChat().isToolCallingEnabled();
        String answer;
        int rounds = 0;

        emitter.accept(SseEvent.status("启动 Agent..."));

        if (protocolEnabled) {
            AgentLangChainService.ToolPolicy toolPolicy = new AgentLangChainService.ToolPolicy(
                    normalizeAllowedTools(request.getAllowedTools()),
                    request.isInternetEnabled()
            );
            AgentLangChainService.AgentRunResult result = agentLangChainService.run(
                    provider, history, request.getMessage(), toolPolicy, emitter);
            answer = normalizeFinalAnswer(result);
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
            emitter.accept(SseEvent.status("生成回答中..."));
            answer = streamAnswer(provider, prompt, emitter);
        }

        memoryPort.append(request.getSessionId(), new ChatMessage("user", request.getMessage()));
        memoryPort.append(request.getSessionId(), new ChatMessage("assistant", answer));

        log.info("agent stream result session={} provider={} protocol={} rounds={} tools={}",
                request.getSessionId(), provider, protocolEnabled, rounds, toolCalls);

        emitter.accept(SseEvent.done(new ChatResponse(
                answer, provider, List.of(), toolCalls, imageUrls,
                protocolEnabled, rounds, roundLatenciesMs, toolErrors,
                "", "", "agent", protocolEnabled ? "agent-tool-protocol" : "agent-direct"
        )));
    }

    private String streamAnswer(String provider, List<ChatMessage> prompt, Consumer<SseEvent> emitter) {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> fallback = new AtomicReference<>(null);

        modelClientService.streamChat(provider, prompt,
                token -> {
                    sb.append(token);
                    emitter.accept(SseEvent.token(token));
                },
                latch::countDown,
                error -> {
                    try {
                        String syncAnswer = modelClientService.chat(provider, prompt);
                        fallback.set(syncAnswer);
                        for (int i = 0; i < syncAnswer.length(); i += 4) {
                            emitter.accept(SseEvent.token(syncAnswer.substring(i, Math.min(i + 4, syncAnswer.length()))));
                        }
                    } catch (Exception ex) {
                        emitter.accept(SseEvent.error(ex.getMessage()));
                    }
                    latch.countDown();
                }
        );

        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fallback.get() != null ? fallback.get() : sb.toString();
    }

    private Set<String> normalizeAllowedTools(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String v = value.trim();
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private String normalizeFinalAnswer(AgentLangChainService.AgentRunResult result) {
        if (result.answer() == null || result.answer().isBlank()) {
            return result.lastToolOutput();
        }
        if (!result.toolErrors().isEmpty()) {
            return result.answer();
        }
        if (result.lastToolOutput() == null || result.lastToolOutput().isBlank()) {
            return result.answer();
        }
        if (!FAILURE_STYLE_PATTERN.matcher(result.answer()).find()) {
            return result.answer();
        }
        // 工具成功时，优先返回工具产出，避免模型误生成“技术问题”类文案。
        return result.lastToolOutput();
    }
}

package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.ToolCallRequest;
import com.eureka.agenthub.model.ToolChatResult;
import com.eureka.agenthub.model.ToolDefinition;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.port.ToolExecutorPort;
import com.eureka.agenthub.service.ModelClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agent 编排最小骨架。
 * <p>
 * P4 阶段先保证“路径分离且可运行”，P5 再接 langchain4j/langgraph4j 完整图编排。
 */
@Component
public class AgentChatOrchestrator implements ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentChatOrchestrator.class);
    private static final Pattern PNG_URL_PATTERN = Pattern.compile("(https?://\\S+\\.png)");

    private static final String AGENT_SYSTEM_PROMPT =
            "You are an agent-style assistant. Keep answers concise and actionable. " +
                    "Use available tools proactively for weather/screenshot/todo/summary requests. " +
                    "If tool output exists, answer based on it instead of saying 'please wait'.";

    private final MemoryPort memoryPort;
    private final ToolExecutorPort toolExecutorPort;
    private final ModelClientService modelClientService;
    private final AppProperties appProperties;

    public AgentChatOrchestrator(MemoryPort memoryPort,
                                 ToolExecutorPort toolExecutorPort,
                                 ModelClientService modelClientService,
                                 AppProperties appProperties) {
        this.memoryPort = memoryPort;
        this.toolExecutorPort = toolExecutorPort;
        this.modelClientService = modelClientService;
        this.appProperties = appProperties;
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
            ProtocolResult protocolResult = runToolProtocol(request.getMessage(), history, provider, toolCalls, toolErrors, imageUrls, roundLatenciesMs);
            answer = protocolResult.answer();
            rounds = protocolResult.rounds();
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

    private ProtocolResult runToolProtocol(String userMessage,
                                           List<ChatMessage> history,
                                           String provider,
                                           List<String> toolCalls,
                                           List<String> toolErrors,
                                           List<String> imageUrls,
                                           List<Long> roundLatenciesMs) {
        List<ToolDefinition> tools = toolExecutorPort.listCallableTools();
        if (tools.isEmpty()) {
            List<ChatMessage> prompt = new ArrayList<>();
            prompt.add(new ChatMessage("system", AGENT_SYSTEM_PROMPT));
            prompt.addAll(history);
            prompt.add(new ChatMessage("user", userMessage));
            return new ProtocolResult(modelClientService.chat(provider, prompt), 0);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", AGENT_SYSTEM_PROMPT));
        for (ChatMessage h : history) {
            messages.add(Map.of("role", h.role(), "content", h.content()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        int maxRounds = Math.max(1, appProperties.getChat().getMaxToolRounds());
        for (int round = 0; round < maxRounds; round++) {
            long started = System.nanoTime();
            ToolChatResult turn = modelClientService.chatWithTools(provider, messages, tools);
            List<ToolCallRequest> calls = turn.toolCalls();
            if (calls == null || calls.isEmpty()) {
                roundLatenciesMs.add((System.nanoTime() - started) / 1_000_000L);
                String content = turn.assistantText();
                if (content == null || content.isBlank()) {
                    break;
                }
                return new ProtocolResult(content, round + 1);
            }

            List<Map<String, Object>> assistantCalls = new ArrayList<>();
            for (ToolCallRequest call : calls) {
                assistantCalls.add(Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", toJsonArguments(call.arguments())
                        )
                ));
            }
            messages.add(Map.of("role", "assistant", "content", turn.assistantText() == null ? "" : turn.assistantText(), "tool_calls", assistantCalls));

            for (ToolCallRequest call : calls) {
                toolCalls.add(call.name());
                String output;
                try {
                    output = toolExecutorPort.callTool(call.name(), call.arguments());
                } catch (Exception e) {
                    output = "tool " + call.name() + " failed: " + (e.getMessage() == null ? "unknown" : e.getMessage());
                }
                String error = detectToolError(call.name(), output);
                if (!error.isBlank()) {
                    toolErrors.add(error);
                }
                String imageUrl = extractPngUrl(output);
                if (!imageUrl.isBlank()) {
                    imageUrls.add(imageUrl);
                }
                messages.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", output == null ? "" : output));
            }

            roundLatenciesMs.add((System.nanoTime() - started) / 1_000_000L);
        }

        List<ChatMessage> fallback = new ArrayList<>();
        fallback.add(new ChatMessage("system", AGENT_SYSTEM_PROMPT));
        fallback.addAll(history);
        fallback.add(new ChatMessage("user", userMessage));
        toolErrors.add("agent tool protocol reached max rounds and fell back to normal chat");
        return new ProtocolResult(modelClientService.chat(provider, fallback), maxRounds);
    }

    private String detectToolError(String toolName, String output) {
        if (output == null || output.isBlank()) {
            return "tool " + toolName + " returned empty output";
        }
        String normalized = output.toLowerCase();
        boolean failed = output.contains("失败") || output.contains("无法")
                || normalized.contains("error") || normalized.contains("failed")
                || normalized.contains("permission") || normalized.contains("headless");
        if (!failed) {
            return "";
        }
        return "tool " + toolName + " reported error: " + output.replaceAll("\\s+", " ").trim();
    }

    private String extractPngUrl(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        var matcher = PNG_URL_PATTERN.matcher(output);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String toJsonArguments(Map<String, Object> args) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record ProtocolResult(String answer, int rounds) {
    }
}

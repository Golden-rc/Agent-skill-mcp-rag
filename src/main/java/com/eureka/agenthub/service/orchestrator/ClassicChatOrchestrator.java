package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.RagHit;
import com.eureka.agenthub.model.ToolCallRequest;
import com.eureka.agenthub.model.ToolChatResult;
import com.eureka.agenthub.model.ToolDefinition;
import com.eureka.agenthub.port.MemoryPort;
import com.eureka.agenthub.port.RetrieverPort;
import com.eureka.agenthub.port.ToolExecutorPort;
import com.eureka.agenthub.service.ModelClientService;
import com.eureka.agenthub.service.RagContextPacker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ClassicChatOrchestrator implements ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClassicChatOrchestrator.class);
    private static final Pattern PURE_MATH_PATTERN = Pattern.compile("^[0-9\\s+\\-*/().=]+$");
    private static final Pattern PNG_URL_PATTERN = Pattern.compile("(https?://\\S+\\.png)");
    private static final String CHAT_SYSTEM_PROMPT =
            "You are an enterprise AI assistant. Answer in Chinese when user uses Chinese. " +
                    "If retrieval context exists, cite source names in the answer. " +
                    "For simple math or short factual questions, answer directly and do not force RAG context.";

    private final MemoryPort memoryPort;
    private final RetrieverPort retrieverPort;
    private final ToolExecutorPort toolExecutorPort;
    private final ModelClientService modelClientService;
    private final AppProperties appProperties;

    public ClassicChatOrchestrator(MemoryPort memoryPort,
                                   RetrieverPort retrieverPort,
                                   ToolExecutorPort toolExecutorPort,
                                   ModelClientService modelClientService,
                                   AppProperties appProperties) {
        this.memoryPort = memoryPort;
        this.retrieverPort = retrieverPort;
        this.toolExecutorPort = toolExecutorPort;
        this.modelClientService = modelClientService;
        this.appProperties = appProperties;
    }

    @Override
    public String key() {
        return OrchestratorRouter.CLASSIC;
    }

    @Override
    public ChatResponse chat(ChatRequest request, String provider) {
        String userMessage = request.getMessage();
        boolean contextualFollowUp = isContextualFollowUp(userMessage);
        String mode = normalizeMode(request.getMode());
        boolean screenshotIntent = isScreenshotIntent(userMessage);
        ModeDecision modeDecision = "auto".equals(mode)
                ? (screenshotIntent
                ? new ModeDecision("direct", "rule-screenshot-tool")
                : classifyAutoMode(provider, userMessage))
                : new ModeDecision(mode, "manual-" + mode);
        String effectiveMode = modeDecision.mode();
        boolean directMode = "direct".equals(effectiveMode);

        List<ChatMessage> history = memoryPort.loadHistory(request.getSessionId());
        if (directMode) {
            int directHistoryLimit = Math.max(0, appProperties.getMemory().getDirectHistoryMessages());
            history = takeLastMessages(history, directHistoryLimit);
        }

        List<RagHit> hits = directMode
                ? Collections.emptyList()
                : retrieverPort.retrieve(userMessage, 5);
        List<RagHit> packedHits = RagContextPacker.pack(
                hits,
                appProperties.getRag().getContextMaxChars(),
                appProperties.getRag().getContextChunkMaxChars()
        );

        List<String> toolCalls = new ArrayList<>();
        List<String> imageUrls = new ArrayList<>();
        List<String> toolErrors = new ArrayList<>();
        List<Long> toolRoundLatenciesMs = new ArrayList<>();
        boolean toolProtocolUsed = false;
        int toolRounds = 0;
        boolean protocolEligible = shouldUseToolProtocol(provider, request.isToolTestMode())
                && (!directMode || screenshotIntent || request.isToolTestMode());

        if (!directMode && !protocolEligible && isInsufficientEvidence(packedHits, contextualFollowUp)) {
            String answer = insufficientEvidenceAnswer();
            memoryPort.append(request.getSessionId(), new ChatMessage("user", userMessage));
            memoryPort.append(request.getSessionId(), new ChatMessage("assistant", answer));
            return new ChatResponse(answer, provider, List.of(), toolCalls, imageUrls,
                    false, 0, toolRoundLatenciesMs, toolErrors,
                    "", "", effectiveMode, "rule-insufficient-evidence");
        }

        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(new ChatMessage("system", CHAT_SYSTEM_PROMPT));
        prompt.addAll(history);
        prompt.add(new ChatMessage("user", buildUserPrompt(userMessage, packedHits, "")));

        String answer;
        if (protocolEligible) {
            ProtocolChatResult protocolResult = chatWithToolProtocol(provider, history, userMessage, packedHits, toolCalls, imageUrls);
            answer = protocolResult.answer();
            toolProtocolUsed = protocolResult.used();
            toolRounds = protocolResult.rounds();
            toolErrors.addAll(protocolResult.errors());
            toolRoundLatenciesMs.addAll(protocolResult.roundLatenciesMs());
        } else {
            if (request.isToolTestMode()) {
                toolErrors.add("tool test mode requires openai provider");
            }
            ToolRouteResult toolRouteResult = (directMode && !screenshotIntent)
                    ? ToolRouteResult.empty()
                    : routeToolIfNeeded(userMessage, toolCalls, imageUrls);
            prompt.set(prompt.size() - 1, new ChatMessage("user", buildUserPrompt(userMessage, packedHits, toolRouteResult.promptContext())));
            answer = modelClientService.chat(provider, prompt);
        }

        memoryPort.append(request.getSessionId(), new ChatMessage("user", userMessage));
        memoryPort.append(request.getSessionId(), new ChatMessage("assistant", answer));

        log.info("classic chat result session={} provider={} mode={} reason={} protocolUsed={} rounds={} tools={} errors={}",
                request.getSessionId(), provider, effectiveMode, modeDecision.reason(),
                toolProtocolUsed, toolRounds, toolCalls, toolErrors);

        return new ChatResponse(answer, provider, toResponseCitations(packedHits), toolCalls, imageUrls,
                toolProtocolUsed, toolRounds, toolRoundLatenciesMs, toolErrors,
                "", "", effectiveMode, modeDecision.reason());
    }

    private ModeDecision classifyAutoMode(String provider, String message) {
        if (isContextualFollowUp(message)) {
            return new ModeDecision("rag", "rule-followup");
        }
        if (isSimpleQuestion(message)) {
            return new ModeDecision("direct", "rule-simple");
        }
        if (isLikelyKnowledgeTask(message)) {
            return new ModeDecision("rag", "rule-keyword");
        }
        try {
            String result = modelClientService.classifyMode(provider, message);
            if (result.contains("direct")) {
                return new ModeDecision("direct", "llm-classifier");
            }
            if (result.contains("rag")) {
                return new ModeDecision("rag", "llm-classifier");
            }
        } catch (Exception ignored) {
        }
        return new ModeDecision("rag", "fallback-rag");
    }

    private String normalizeMode(String requestMode) {
        String mode = requestMode == null ? "auto" : requestMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "auto", "direct", "rag" -> mode;
            default -> throw new IllegalArgumentException("unsupported mode: " + requestMode);
        };
    }

    private boolean isSimpleQuestion(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (PURE_MATH_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return trimmed.length() <= 20 && (lower.equals("1+1") || lower.equals("2+2") || lower.equals("123"));
    }

    private boolean isLikelyKnowledgeTask(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("知识库") || normalized.contains("基于") || normalized.contains("引用")
                || normalized.contains("方案") || normalized.contains("计划") || normalized.contains("总结")
                || normalized.contains("待办") || normalized.contains("rag") || normalized.contains("agent");
    }

    private boolean isScreenshotIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("截图") || normalized.contains("截屏") || normalized.contains("screenshot")
                || normalized.contains("screen shot") || normalized.contains("screen capture") || normalized.contains("屏幕");
    }

    private boolean isContextualFollowUp(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("上一个") || normalized.contains("刚才") || normalized.contains("继续")
                || normalized.contains("前面") || normalized.contains("上文")
                || normalized.contains("这个问题") || normalized.contains("那个问题");
    }

    private boolean isInsufficientEvidence(List<RagHit> hits, boolean contextualFollowUp) {
        if (contextualFollowUp) {
            return false;
        }
        int minEvidence = Math.max(1, appProperties.getRag().getMinEvidenceCount());
        return hits == null || hits.size() < minEvidence;
    }

    private String insufficientEvidenceAnswer() {
        return "我暂时没有在知识库中找到足够依据来回答这个问题。\n"
                + "建议：\n"
                + "1) 换一种更具体的问法（补充对象/范围/时间）\n"
                + "2) 先上传相关文档后再提问\n"
                + "3) 如果你希望我基于已有对话继续，请直接说明“按上文继续这个问题”。";
    }

    private ToolRouteResult routeToolIfNeeded(String message, List<String> toolCalls, List<String> imageUrls) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (isScreenshotIntent(message)) {
            toolCalls.add("take_screenshot");
            String toolOutput = toolExecutorPort.callTool("take_screenshot", Map.of("text", message));
            String imageUrl = extractPngUrl(toolOutput);
            if (!imageUrl.isBlank()) {
                imageUrls.add(imageUrl);
                return new ToolRouteResult("Screenshot captured successfully.");
            }
            return new ToolRouteResult(toolOutput);
        }
        if (normalized.contains("todo") || message.contains("待办") || message.contains("行动项")) {
            toolCalls.add("extract_todos");
            return new ToolRouteResult(toolExecutorPort.callTool("extract_todos", Map.of("text", message)));
        }
        if (normalized.contains("summary") || message.contains("总结") || message.contains("概括")) {
            toolCalls.add("summarize");
            return new ToolRouteResult(toolExecutorPort.callTool("summarize", Map.of("text", message)));
        }
        return ToolRouteResult.empty();
    }

    private boolean shouldUseToolProtocol(String provider, boolean forceByTestMode) {
        if (forceByTestMode) {
            return "openai".equals(provider);
        }
        if (!appProperties.getChat().isToolCallingEnabled()) {
            return false;
        }
        if (appProperties.getChat().isToolCallingOpenaiOnly()) {
            return "openai".equals(provider);
        }
        return true;
    }

    private ProtocolChatResult chatWithToolProtocol(String provider,
                                                    List<ChatMessage> history,
                                                    String userInput,
                                                    List<RagHit> hits,
                                                    List<String> toolCalls,
                                                    List<String> imageUrls) {
        List<String> toolErrors = new ArrayList<>();
        List<Long> roundLatenciesMs = new ArrayList<>();
        List<ToolDefinition> tools = toolExecutorPort.listCallableTools();
        if (tools.isEmpty()) {
            List<ChatMessage> prompt = new ArrayList<>();
            prompt.add(new ChatMessage("system", CHAT_SYSTEM_PROMPT));
            prompt.addAll(history);
            prompt.add(new ChatMessage("user", buildUserPrompt(userInput, hits, "")));
            return new ProtocolChatResult(modelClientService.chat(provider, prompt), false, 0, roundLatenciesMs, toolErrors);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", CHAT_SYSTEM_PROMPT));
        for (ChatMessage h : history) {
            messages.add(Map.of("role", h.role(), "content", h.content()));
        }
        messages.add(Map.of("role", "user", "content", buildUserPrompt(userInput, hits, "")));

        int maxRounds = Math.max(1, appProperties.getChat().getMaxToolRounds());
        for (int round = 0; round < maxRounds; round++) {
            long roundStart = System.nanoTime();
            ToolChatResult turn = modelClientService.chatWithTools(provider, messages, tools);
            List<ToolCallRequest> calls = turn.toolCalls();
            if (calls == null || calls.isEmpty()) {
                roundLatenciesMs.add((System.nanoTime() - roundStart) / 1_000_000L);
                String text = turn.assistantText();
                if (text == null || text.isBlank()) {
                    break;
                }
                return new ProtocolChatResult(text, true, round + 1, roundLatenciesMs, toolErrors);
            }

            List<Map<String, Object>> assistantToolCalls = new ArrayList<>();
            for (ToolCallRequest call : calls) {
                assistantToolCalls.add(Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of("name", call.name(), "arguments", toJsonArguments(call.arguments()))
                ));
            }

            Map<String, Object> assistantMessage = new HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", turn.assistantText() == null ? "" : turn.assistantText());
            assistantMessage.put("tool_calls", assistantToolCalls);
            messages.add(assistantMessage);

            for (ToolCallRequest call : calls) {
                toolCalls.add(call.name());
                String output;
                boolean failedByException = false;
                try {
                    output = toolExecutorPort.callTool(call.name(), call.arguments());
                } catch (Exception e) {
                    String error = "tool " + call.name() + " failed: " + (e.getMessage() == null ? "unknown" : e.getMessage());
                    toolErrors.add(error);
                    output = error;
                    failedByException = true;
                }
                String outputError = detectToolOutputError(call.name(), output);
                if (!failedByException && !outputError.isBlank()) {
                    toolErrors.add(outputError);
                }
                String imageUrl = extractPngUrl(output);
                if (!imageUrl.isBlank()) {
                    imageUrls.add(imageUrl);
                }
                messages.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", output == null ? "" : output));
            }
            roundLatenciesMs.add((System.nanoTime() - roundStart) / 1_000_000L);
        }

        List<ChatMessage> fallbackPrompt = new ArrayList<>();
        fallbackPrompt.add(new ChatMessage("system", CHAT_SYSTEM_PROMPT));
        fallbackPrompt.addAll(history);
        fallbackPrompt.add(new ChatMessage("user", buildUserPrompt(userInput, hits, "")));
        toolErrors.add("tool protocol reached max rounds and fell back to normal chat");
        log.warn("tool protocol fallback after max rounds={}, userInput={}", maxRounds, limitLength(userInput, 60));
        return new ProtocolChatResult(modelClientService.chat(provider, fallbackPrompt), true, maxRounds, roundLatenciesMs, toolErrors);
    }

    private String detectToolOutputError(String toolName, String output) {
        if (output == null || output.isBlank()) {
            return "tool " + toolName + " returned empty output";
        }
        String normalized = output.toLowerCase(Locale.ROOT);
        boolean failed = output.contains("失败")
                || output.contains("无法")
                || normalized.contains("error")
                || normalized.contains("failed")
                || normalized.contains("headless")
                || normalized.contains("denied")
                || normalized.contains("permission");
        if (!failed) {
            return "";
        }
        return "tool " + toolName + " reported error: " + limitLength(output.replaceAll("\\s+", " ").trim(), 120);
    }

    private String extractPngUrl(String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = PNG_URL_PATTERN.matcher(toolOutput);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String toJsonArguments(Map<String, Object> arguments) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildUserPrompt(String userInput, List<RagHit> hits, String toolResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("User input:\n").append(userInput).append("\n\n");
        if (!hits.isEmpty()) {
            sb.append("RAG context:\n");
            for (int i = 0; i < hits.size(); i++) {
                RagHit hit = hits.get(i);
                sb.append(i + 1).append(") source=").append(hit.source())
                        .append(" score=").append(String.format(Locale.ROOT, "%.4f", hit.score()))
                        .append("\n").append(limitLength(hit.content(), 900)).append("\n\n");
            }
        }
        if (!toolResult.isBlank()) {
            sb.append("Tool output:\n").append(toolResult).append("\n\n");
        }
        sb.append("Please provide a concise and actionable answer.");
        return sb.toString();
    }

    private String limitLength(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private List<ChatMessage> takeLastMessages(List<ChatMessage> messages, int limit) {
        if (messages == null || messages.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        if (messages.size() <= limit) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - limit, messages.size()));
    }

    private List<RagHit> toResponseCitations(List<RagHit> hits) {
        int maxChars = Math.max(80, appProperties.getRag().getCitationMaxChars());
        List<RagHit> compact = new ArrayList<>(hits.size());
        for (RagHit hit : hits) {
            String content = hit.content() == null ? "" : hit.content().replaceAll("\\s+", " ").trim();
            compact.add(new RagHit(hit.source(), limitLength(content, maxChars), hit.score()));
        }
        return compact;
    }

    private record ModeDecision(String mode, String reason) {
    }

    private record ToolRouteResult(String promptContext) {
        private static ToolRouteResult empty() {
            return new ToolRouteResult("");
        }
    }

    private record ProtocolChatResult(String answer,
                                      boolean used,
                                      int rounds,
                                      List<Long> roundLatenciesMs,
                                      List<String> errors) {
    }
}

package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.RagHit;
import com.eureka.agenthub.model.ToolCallRequest;
import com.eureka.agenthub.model.ToolChatResult;
import com.eureka.agenthub.model.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
/**
 * 聊天编排服务。
 * <p>
 * 主流程：模型路由 -> 读取会话记忆 -> RAG 检索 -> 可选工具调用 -> LLM 生成 -> 写回记忆。
 */
public class ChatService {

    private static final Pattern PURE_MATH_PATTERN = Pattern.compile("^[0-9\\s+\\-*/().=]+$");
    private static final Pattern PNG_URL_PATTERN = Pattern.compile("(https?://\\S+\\.png)");
    private static final String CHAT_SYSTEM_PROMPT =
            "You are an enterprise AI assistant. Answer in Chinese when user uses Chinese. " +
                    "If retrieval context exists, cite source names in the answer. " +
                    "For simple math or short factual questions, answer directly and do not force RAG context.";

    private final ProviderRouter providerRouter;
    private final MemoryService memoryService;
    private final RagService ragService;
    private final McpClientService mcpClientService;
    private final ModelClientService modelClientService;
    private final AppProperties appProperties;

    public ChatService(ProviderRouter providerRouter,
                       MemoryService memoryService,
                       RagService ragService,
                       McpClientService mcpClientService,
                       ModelClientService modelClientService,
                       AppProperties appProperties) {
        this.providerRouter = providerRouter;
        this.memoryService = memoryService;
        this.ragService = ragService;
        this.mcpClientService = mcpClientService;
        this.modelClientService = modelClientService;
        this.appProperties = appProperties;
    }

    public ChatResponse chat(ChatRequest request) {
        // 1) 选择模型提供方。
        String provider = providerRouter.pickProvider(request.getProvider());
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

        // 2) 两种模式都支持会话记忆。
        // - rag: 使用完整历史窗口。
        // - direct: 使用最近 N 条，既保留上下文连续性，又降低旧上下文污染。
        List<ChatMessage> history = memoryService.loadHistory(request.getSessionId());
        if (directMode) {
            int directHistoryLimit = Math.max(0, appProperties.getMemory().getDirectHistoryMessages());
            history = takeLastMessages(history, directHistoryLimit);
        }

        // 3) 严格直答跳过 RAG；知识库增强启用 RAG。
        List<RagHit> hits = directMode
                ? Collections.emptyList()
                : ragService.retrieve(userMessage, 5);
        List<RagHit> packedHits = RagContextPacker.pack(
                hits,
                appProperties.getRag().getContextMaxChars(),
                appProperties.getRag().getContextChunkMaxChars()
        );

        List<String> toolCalls = new ArrayList<>();
        List<String> imageUrls = new ArrayList<>();

        if (!directMode && isInsufficientEvidence(packedHits, contextualFollowUp)) {
            String answer = insufficientEvidenceAnswer(userMessage);
            memoryService.append(request.getSessionId(), new ChatMessage("user", userMessage));
            memoryService.append(request.getSessionId(), new ChatMessage("assistant", answer));
            return new ChatResponse(answer, provider, List.of(), toolCalls, imageUrls, effectiveMode, "rule-insufficient-evidence");
        }

        // 4) 组装最终 prompt。
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(new ChatMessage("system", CHAT_SYSTEM_PROMPT));
        prompt.addAll(history);
        prompt.add(new ChatMessage("user", buildUserPrompt(userMessage, packedHits, "")));

        // 5) 协议化工具调用（openai）或兼容旧的关键词路由。
        String answer;
        if (shouldUseToolProtocol(provider) && (!directMode || screenshotIntent)) {
            answer = chatWithToolProtocol(provider, history, userMessage, packedHits, toolCalls, imageUrls);
        } else {
            ToolRouteResult toolRouteResult = (directMode && !screenshotIntent)
                    ? ToolRouteResult.empty()
                    : routeToolIfNeeded(userMessage, toolCalls, imageUrls);
            prompt.set(prompt.size() - 1, new ChatMessage("user", buildUserPrompt(userMessage, packedHits, toolRouteResult.promptContext())));
            answer = modelClientService.chat(provider, prompt);
        }

        // 7) 写回会话记忆。
        memoryService.append(request.getSessionId(), new ChatMessage("user", userMessage));
        memoryService.append(request.getSessionId(), new ChatMessage("assistant", answer));

        return new ChatResponse(answer, provider, toResponseCitations(packedHits), toolCalls, imageUrls, effectiveMode, modeDecision.reason());
    }

    private ModeDecision classifyAutoMode(String provider, String message) {
        // 第一层：快速规则，优先拦截简单问题。
        if (isContextualFollowUp(message)) {
            return new ModeDecision("rag", "rule-followup");
        }
        if (isSimpleQuestion(message)) {
            return new ModeDecision("direct", "rule-simple");
        }

        // 第二层：显式知识库任务关键词，优先走增强模式。
        if (isLikelyKnowledgeTask(message)) {
            return new ModeDecision("rag", "rule-keyword");
        }

        // 第三层：让模型做轻量意图分类。
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

        // 默认偏向知识增强，避免遗漏业务上下文。
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
        return normalized.contains("知识库")
                || normalized.contains("基于")
                || normalized.contains("引用")
                || normalized.contains("方案")
                || normalized.contains("计划")
                || normalized.contains("总结")
                || normalized.contains("待办")
                || normalized.contains("rag")
                || normalized.contains("agent");
    }

    private boolean isScreenshotIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("截图")
                || normalized.contains("截屏")
                || normalized.contains("screenshot")
                || normalized.contains("screen shot")
                || normalized.contains("screen capture")
                || normalized.contains("屏幕");
    }

    private boolean isContextualFollowUp(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("上一个")
                || normalized.contains("刚才")
                || normalized.contains("继续")
                || normalized.contains("前面")
                || normalized.contains("上文")
                || normalized.contains("这个问题")
                || normalized.contains("那个问题");
    }

    private boolean isInsufficientEvidence(List<RagHit> hits, boolean contextualFollowUp) {
        if (contextualFollowUp) {
            // 指代型追问可优先依赖会话历史，不强制要求 RAG 命中条数。
            return false;
        }
        int minEvidence = Math.max(1, appProperties.getRag().getMinEvidenceCount());
        return hits == null || hits.size() < minEvidence;
    }

    private String insufficientEvidenceAnswer(String userInput) {
        return "我暂时没有在知识库中找到足够依据来回答这个问题。\n"
                + "建议：\n"
                + "1) 换一种更具体的问法（补充对象/范围/时间）\n"
                + "2) 先上传相关文档后再提问\n"
                + "3) 如果你希望我基于已有对话继续，请直接说明“按上文继续这个问题”。";
    }

    private record ModeDecision(String mode, String reason) {
    }

    private ToolRouteResult routeToolIfNeeded(String message, List<String> toolCalls, List<String> imageUrls) {
        // 当前为轻量规则路由，可后续替换成意图分类器。
        String normalized = message.toLowerCase(Locale.ROOT);
        if (isScreenshotIntent(message)) {
            toolCalls.add("take_screenshot");
            String toolOutput = mcpClientService.callTool("take_screenshot", message);
            String imageUrl = extractPngUrl(toolOutput);
            if (!imageUrl.isBlank()) {
                imageUrls.add(imageUrl);
                return new ToolRouteResult("Screenshot captured successfully.");
            }
            return new ToolRouteResult(toolOutput);
        }
        if (normalized.contains("todo") || message.contains("待办") || message.contains("行动项")) {
            toolCalls.add("extract_todos");
            return new ToolRouteResult(mcpClientService.callTool("extract_todos", message));
        }
        if (normalized.contains("summary") || message.contains("总结") || message.contains("概括")) {
            toolCalls.add("summarize");
            return new ToolRouteResult(mcpClientService.callTool("summarize", message));
        }
        return ToolRouteResult.empty();
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

    private record ToolRouteResult(String promptContext) {
        private static ToolRouteResult empty() {
            return new ToolRouteResult("");
        }
    }

    private boolean shouldUseToolProtocol(String provider) {
        if (!appProperties.getChat().isToolCallingEnabled()) {
            return false;
        }
        if (appProperties.getChat().isToolCallingOpenaiOnly()) {
            return "openai".equals(provider);
        }
        return true;
    }

    private String chatWithToolProtocol(String provider,
                                        List<ChatMessage> history,
                                        String userInput,
                                        List<RagHit> hits,
                                        List<String> toolCalls,
                                        List<String> imageUrls) {
        List<ToolDefinition> tools = mcpClientService.listCallableTools();
        if (tools.isEmpty()) {
            List<ChatMessage> prompt = new ArrayList<>();
            prompt.add(new ChatMessage("system", CHAT_SYSTEM_PROMPT));
            prompt.addAll(history);
            prompt.add(new ChatMessage("user", buildUserPrompt(userInput, hits, "")));
            return modelClientService.chat(provider, prompt);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", CHAT_SYSTEM_PROMPT));
        for (ChatMessage h : history) {
            messages.add(Map.of(
                    "role", h.role(),
                    "content", h.content()
            ));
        }
        messages.add(Map.of("role", "user", "content", buildUserPrompt(userInput, hits, "")));

        int maxRounds = Math.max(1, appProperties.getChat().getMaxToolRounds());
        for (int round = 0; round < maxRounds; round++) {
            ToolChatResult turn = modelClientService.chatWithTools(provider, messages, tools);
            List<ToolCallRequest> calls = turn.toolCalls();
            if (calls == null || calls.isEmpty()) {
                String text = turn.assistantText();
                if (text == null || text.isBlank()) {
                    break;
                }
                return text;
            }

            List<Map<String, Object>> assistantToolCalls = new ArrayList<>();
            for (ToolCallRequest call : calls) {
                assistantToolCalls.add(Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", toJsonArguments(call.arguments())
                        )
                ));
            }

            Map<String, Object> assistantMessage = new HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", turn.assistantText() == null ? "" : turn.assistantText());
            assistantMessage.put("tool_calls", assistantToolCalls);
            messages.add(assistantMessage);

            for (ToolCallRequest call : calls) {
                toolCalls.add(call.name());
                String output = mcpClientService.callTool(call.name(), call.arguments());
                String imageUrl = extractPngUrl(output);
                if (!imageUrl.isBlank()) {
                    imageUrls.add(imageUrl);
                }

                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", call.id(),
                        "content", output == null ? "" : output
                ));
            }
        }
        List<ChatMessage> fallbackPrompt = new ArrayList<>();
        fallbackPrompt.add(new ChatMessage("system", CHAT_SYSTEM_PROMPT));
        fallbackPrompt.addAll(history);
        fallbackPrompt.add(new ChatMessage("user", buildUserPrompt(userInput, hits, "")));
        return modelClientService.chat(provider, fallbackPrompt);
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
            // 将检索命中按 source + score 结构化拼接，便于模型引用。
            sb.append("RAG context:\n");
            for (int i = 0; i < hits.size(); i++) {
                RagHit hit = hits.get(i);
                sb.append(i + 1)
                        .append(") source=")
                        .append(hit.source())
                        .append(" score=")
                        .append(String.format(Locale.ROOT, "%.4f", hit.score()))
                        .append("\n")
                        .append(limitLength(hit.content(), 900))
                        .append("\n\n");
            }
        }

        if (!toolResult.isBlank()) {
            // 工具执行结果追加到上下文。
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
}

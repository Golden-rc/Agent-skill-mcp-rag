package com.eureka.agenthub.service;

import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.RagHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
/**
 * 聊天编排服务。
 * <p>
 * 主流程：模型路由 -> 读取会话记忆 -> RAG 检索 -> 可选工具调用 -> LLM 生成 -> 写回记忆。
 */
public class ChatService {

    private static final Pattern PURE_MATH_PATTERN = Pattern.compile("^[0-9\\s+\\-*/().=]+$");

    private final ProviderRouter providerRouter;
    private final MemoryService memoryService;
    private final RagService ragService;
    private final McpClientService mcpClientService;
    private final ModelClientService modelClientService;

    public ChatService(ProviderRouter providerRouter,
                       MemoryService memoryService,
                       RagService ragService,
                       McpClientService mcpClientService,
                       ModelClientService modelClientService) {
        this.providerRouter = providerRouter;
        this.memoryService = memoryService;
        this.ragService = ragService;
        this.mcpClientService = mcpClientService;
        this.modelClientService = modelClientService;
    }

    public ChatResponse chat(ChatRequest request) {
        // 1) 选择模型提供方。
        String provider = providerRouter.pickProvider(request.getProvider());
        String userMessage = request.getMessage();
        String mode = normalizeMode(request.getMode());
        ModeDecision modeDecision = "auto".equals(mode)
                ? classifyAutoMode(provider, userMessage)
                : new ModeDecision(mode, "manual-" + mode);
        String effectiveMode = modeDecision.mode();
        boolean directMode = "direct".equals(effectiveMode);

        // 2) 严格直答模式不带历史，避免被旧上下文污染。
        List<ChatMessage> history = directMode ? Collections.emptyList() : memoryService.loadHistory(request.getSessionId());

        // 3) 严格直答跳过 RAG；知识库增强启用 RAG。
        List<RagHit> hits = directMode
                ? Collections.emptyList()
                : ragService.retrieve(userMessage, 3);

        List<String> toolCalls = new ArrayList<>();

        // 4) 简单意图路由，按关键词触发 MCP 工具。
        String toolResult = directMode ? "" : routeToolIfNeeded(userMessage, toolCalls);

        // 5) 组装最终 prompt。
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(new ChatMessage("system",
                "You are an enterprise AI assistant. Answer in Chinese when user uses Chinese. " +
                        "If retrieval context exists, cite source names in the answer. " +
                        "For simple math or short factual questions, answer directly and do not force RAG context."));
        prompt.addAll(history);
        prompt.add(new ChatMessage("user", buildUserPrompt(userMessage, hits, toolResult)));

        // 6) 调用模型生成回答。
        String answer = modelClientService.chat(provider, prompt);

        // 7) 写回会话记忆。
        memoryService.append(request.getSessionId(), new ChatMessage("user", userMessage));
        memoryService.append(request.getSessionId(), new ChatMessage("assistant", answer));

        return new ChatResponse(answer, provider, hits, toolCalls, effectiveMode, modeDecision.reason());
    }

    private ModeDecision classifyAutoMode(String provider, String message) {
        // 第一层：快速规则，优先拦截简单问题。
        if (isSimpleQuestion(message)) {
            return new ModeDecision("direct", "rule-simple");
        }

        // 第二层：显式知识库任务关键词，优先走增强模式。
        if (isLikelyKnowledgeTask(message)) {
            return new ModeDecision("rag", "rule-keyword");
        }

        // 第三层：让模型做轻量意图分类。
        try {
            List<ChatMessage> prompt = List.of(
                    new ChatMessage("system",
                            "You are an intent classifier. Output exactly one word: direct or rag. " +
                                    "Choose direct for simple calculation/chit-chat/short factual answer. " +
                                    "Choose rag for knowledge-base dependent requests, planning with references, " +
                                    "or anything likely requiring retrieval context."),
                    new ChatMessage("user", message)
            );
            String result = modelClientService.chat(provider, prompt).trim().toLowerCase(Locale.ROOT);
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

    private record ModeDecision(String mode, String reason) {
    }

    private String routeToolIfNeeded(String message, List<String> toolCalls) {
        // 当前为轻量规则路由，可后续替换成意图分类器。
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("todo") || message.contains("待办") || message.contains("行动项")) {
            toolCalls.add("extract_todos");
            return mcpClientService.callTool("extract_todos", message);
        }
        if (normalized.contains("summary") || message.contains("总结") || message.contains("概括")) {
            toolCalls.add("summarize");
            return mcpClientService.callTool("summarize", message);
        }
        return "";
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
                        .append(hit.content())
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
}

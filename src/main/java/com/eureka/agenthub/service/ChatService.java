package com.eureka.agenthub.service;

import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.RagHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ChatService {

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
        String provider = providerRouter.pickProvider(request.getProvider());
        List<ChatMessage> history = memoryService.loadHistory(request.getSessionId());
        List<RagHit> hits = ragService.retrieve(request.getMessage(), 3);
        List<String> toolCalls = new ArrayList<>();

        String toolResult = routeToolIfNeeded(request.getMessage(), toolCalls);

        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(new ChatMessage("system",
                "You are an enterprise AI assistant. Answer in Chinese when user uses Chinese. " +
                        "If retrieval context exists, cite source names in the answer."));
        prompt.addAll(history);
        prompt.add(new ChatMessage("user", buildUserPrompt(request.getMessage(), hits, toolResult)));

        String answer = modelClientService.chat(provider, prompt);

        memoryService.append(request.getSessionId(), new ChatMessage("user", request.getMessage()));
        memoryService.append(request.getSessionId(), new ChatMessage("assistant", answer));

        return new ChatResponse(answer, provider, hits, toolCalls);
    }

    private String routeToolIfNeeded(String message, List<String> toolCalls) {
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
            sb.append("Tool output:\n").append(toolResult).append("\n\n");
        }

        sb.append("Please provide a concise and actionable answer.");
        return sb.toString();
    }
}

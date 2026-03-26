package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OrchestratorRouter {

    public static final String CLASSIC = "classic";
    public static final String AGENT = "agent";

    private final Map<String, ChatOrchestrator> orchestrators;
    private final AppProperties appProperties;

    public OrchestratorRouter(List<ChatOrchestrator> orchestrators,
                              AppProperties appProperties) {
        this.orchestrators = new LinkedHashMap<>();
        for (ChatOrchestrator orchestrator : orchestrators) {
            this.orchestrators.put(orchestrator.key(), orchestrator);
        }
        this.appProperties = appProperties;
    }

    public Decision decide(ChatRequest request) {
        String requestValue = normalize(request.getOrchestrator());
        if (!requestValue.isBlank()) {
            return applyWithFallback(requestValue, "request");
        }

        String configValue = normalize(appProperties.getChat().getOrchestrator());
        if (!configValue.isBlank()) {
            return applyWithFallback(configValue, "config");
        }

        return applyWithFallback(CLASSIC, "default");
    }

    private Decision applyWithFallback(String desired, String source) {
        String normalized = normalize(desired);
        if (!orchestrators.containsKey(normalized)) {
            return new Decision(CLASSIC, source + "-fallback-unknown");
        }
        if (AGENT.equals(normalized) && !appProperties.getChat().isAgentEnabled()) {
            return new Decision(CLASSIC, source + "-fallback-agent-disabled");
        }
        return new Decision(normalized, source);
    }

    public ChatOrchestrator get(String key) {
        ChatOrchestrator orchestrator = orchestrators.get(key);
        if (orchestrator == null) {
            return orchestrators.get(CLASSIC);
        }
        return orchestrator;
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (CLASSIC.equals(value) || AGENT.equals(value)) {
            return value;
        }
        return "";
    }

    public record Decision(String orchestratorUsed, String orchestratorReason) {
    }
}

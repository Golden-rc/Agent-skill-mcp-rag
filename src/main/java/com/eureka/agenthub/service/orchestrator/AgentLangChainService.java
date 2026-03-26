package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.port.ToolExecutorPort;
import com.eureka.agenthub.service.ModelClientService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AgentLangChainService {

    private static final Pattern PNG_URL_PATTERN = Pattern.compile("(https?://\\S+\\.png)");

    private final ModelClientService modelClientService;
    private final ToolExecutorPort toolExecutorPort;

    public AgentLangChainService(ModelClientService modelClientService,
                                 ToolExecutorPort toolExecutorPort) {
        this.modelClientService = modelClientService;
        this.toolExecutorPort = toolExecutorPort;
    }

    public AgentRunResult run(String provider,
                              List<ChatMessage> history,
                              String userInput) {
        ToolCallCollector collector = new ToolCallCollector();
        AgentTools tools = new AgentTools(toolExecutorPort, collector);
        AgentAssistant assistant = AiServices.builder(AgentAssistant.class)
                .chatLanguageModel(modelClientService.chatModel(provider))
                .tools(tools)
                .build();

        long started = System.nanoTime();
        String answer = assistant.chat(buildAgentInput(history, userInput));
        long latency = (System.nanoTime() - started) / 1_000_000L;

        return new AgentRunResult(
                answer,
                collector.toolCalls(),
                collector.toolErrors(),
                collector.imageUrls(),
                latency,
                collector.toolCalls().isEmpty() ? 0 : 1,
                true
        );
    }

    private String buildAgentInput(List<ChatMessage> history, String userInput) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            sb.append("Recent conversation:\n");
            int from = Math.max(0, history.size() - 8);
            for (int i = from; i < history.size(); i++) {
                ChatMessage m = history.get(i);
                sb.append("- ")
                        .append(m.role())
                        .append(": ")
                        .append(m.content())
                        .append("\n");
            }
            sb.append("\n");
        }
        sb.append("User question: ").append(userInput);
        return sb.toString();
    }

    @SystemMessage("""
            You are an agent-style assistant.
            Use tools proactively for weather/screenshot/todo/summary tasks.
            If tool output is available, answer directly from tool output.
            Do not reply with 'please wait'.
            """)
    interface AgentAssistant {
        String chat(String message);
    }

    static class AgentTools {
        private final ToolExecutorPort toolExecutorPort;
        private final ToolCallCollector collector;

        AgentTools(ToolExecutorPort toolExecutorPort, ToolCallCollector collector) {
            this.toolExecutorPort = toolExecutorPort;
            this.collector = collector;
        }

        @Tool("Query current weather by city name")
        public String queryWeather(@P("City name") String city) {
            return invoke("query_weather", Map.of("city", city));
        }

        @Tool("Take a screenshot of current screen")
        public String takeScreenshot(@P("Screenshot intent text") String text) {
            return invoke("take_screenshot", Map.of("text", text == null ? "截图" : text));
        }

        @Tool("Summarize input text")
        public String summarize(@P("Text to summarize") String text) {
            return invoke("summarize", Map.of("text", text == null ? "" : text));
        }

        @Tool("Extract todos from input text")
        public String extractTodos(@P("Text to extract todos") String text) {
            return invoke("extract_todos", Map.of("text", text == null ? "" : text));
        }

        private String invoke(String toolName, Map<String, Object> args) {
            String output;
            try {
                output = toolExecutorPort.callTool(toolName, args);
            } catch (Exception e) {
                output = "tool " + toolName + " failed: " + (e.getMessage() == null ? "unknown" : e.getMessage());
            }
            collector.record(toolName, output);
            return output == null ? "" : output;
        }
    }

    static class ToolCallCollector {
        private final List<String> toolCalls = new ArrayList<>();
        private final List<String> toolErrors = new ArrayList<>();
        private final List<String> imageUrls = new ArrayList<>();

        void record(String toolName, String output) {
            toolCalls.add(toolName);
            String error = detectToolError(toolName, output);
            if (!error.isBlank()) {
                toolErrors.add(error);
            }
            String imageUrl = extractPngUrl(output);
            if (!imageUrl.isBlank()) {
                imageUrls.add(imageUrl);
            }
        }

        List<String> toolCalls() {
            return toolCalls;
        }

        List<String> toolErrors() {
            return toolErrors;
        }

        List<String> imageUrls() {
            return imageUrls;
        }

        private String detectToolError(String toolName, String output) {
            if (output == null || output.isBlank()) {
                return "tool " + toolName + " returned empty output";
            }
            String normalized = output.toLowerCase(Locale.ROOT);
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
    }

    public record AgentRunResult(String answer,
                                 List<String> toolCalls,
                                 List<String> toolErrors,
                                 List<String> imageUrls,
                                 long latencyMs,
                                 int rounds,
                                 boolean protocolUsed) {
    }
}

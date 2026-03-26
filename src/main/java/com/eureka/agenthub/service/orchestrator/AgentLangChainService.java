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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Service
/**
 * Agent 的 LangChain4j 执行层。
 * <p>
 * 使用 AiServices + @Tool 将模型工具调用与 MCP ToolExecutorPort 对接，
 * 并统一收集工具调用、错误和图片产物用于前端可视化调试。
 */
public class AgentLangChainService {

    private static final Pattern PNG_URL_PATTERN = Pattern.compile("(https?://\\S+\\.png)");
    private static final Pattern MODEL_FAILURE_PATTERN = Pattern.compile(
            "(技术问题|暂时无法|稍后再试|technical issue|temporarily unavailable|try again later)",
            Pattern.CASE_INSENSITIVE
    );

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
        // 每次请求都创建新的工具收集器，避免并发会话污染。
        ToolCallCollector collector = new ToolCallCollector();
        AgentTools tools = new AgentTools(toolExecutorPort, collector);
        AgentAssistant assistant = AiServices.builder(AgentAssistant.class)
                .chatLanguageModel(modelClientService.chatModel(provider))
                .tools(tools)
                .build();

        long started = System.nanoTime();
        String answer = assistant.chat(buildAgentInput(history, userInput));
        long latency = (System.nanoTime() - started) / 1_000_000L;
        String normalizedAnswer = normalizeAnswer(answer, collector);

        return new AgentRunResult(
                normalizedAnswer,
                collector.toolCalls(),
                collector.toolErrors(),
                collector.imageUrls(),
                latency,
                collector.toolCalls().isEmpty() ? 0 : 1,
                true,
                collector.lastSuccessfulOutput()
        );
    }

    private String normalizeAnswer(String answer, ToolCallCollector collector) {
        if (answer == null || answer.isBlank()) {
            return collector.lastSuccessfulOutput();
        }
        if (!collector.toolErrors().isEmpty()) {
            return answer;
        }
        if (!collector.hasSuccessfulOutput()) {
            return answer;
        }
        if (!MODEL_FAILURE_PATTERN.matcher(answer).find()) {
            return answer;
        }
        return collector.lastSuccessfulOutput();
    }

    private String buildAgentInput(List<ChatMessage> history, String userInput) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            // 先注入最近上下文，帮助 agent 处理“继续/上一个问题”等追问。
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
        private final Map<String, String> memoizedResults = new HashMap<>();

        AgentTools(ToolExecutorPort toolExecutorPort, ToolCallCollector collector) {
            this.toolExecutorPort = toolExecutorPort;
            this.collector = collector;
        }

        @Tool("Query current weather by city name")
        public String queryWeather(@P("City name") String city) {
            return invoke("query_weather", Map.of("city", city));
        }

        @Tool("Query weather forecast for coming days by city name")
        public String queryWeatherForecast(@P("City name") String city,
                                           @P("Forecast days, 1-7") Integer days) {
            int safeDays = days == null ? 3 : Math.max(1, Math.min(days, 7));
            return invoke("query_weather_forecast", Map.of("city", city, "days", safeDays));
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

        @Tool("Fetch and extract readable text from a web page")
        public String webFetch(@P("Target URL") String url,
                               @P("Maximum returned characters") Integer maxChars) {
            int safeMax = maxChars == null ? 2000 : Math.max(300, Math.min(maxChars, 10000));
            return invoke("web_fetch", Map.of("url", url, "maxChars", safeMax));
        }

        @Tool("Translate text to target language")
        public String translateText(@P("Text to translate") String text,
                                    @P("Target language code, e.g. zh-CN, en, ja") String targetLang,
                                    @P("Source language code, optional") String sourceLang) {
            String src = (sourceLang == null || sourceLang.isBlank()) ? "auto" : sourceLang;
            return invoke("translate_text", Map.of("text", text, "targetLang", targetLang, "sourceLang", src));
        }

        private String invoke(String toolName, Map<String, Object> args) {
            String signature = buildSignature(toolName, args);
            // 同一轮 agent 推理中，重复同参工具调用直接复用结果，避免浪费配额或重复副作用。
            if (memoizedResults.containsKey(signature)) {
                return memoizedResults.get(signature);
            }

            String output;
            try {
                output = toolExecutorPort.callTool(toolName, args);
            } catch (Exception e) {
                // 工具异常不抛出到上层，转为可读字符串交给 agent 继续推理。
                output = "tool " + toolName + " failed: " + (e.getMessage() == null ? "unknown" : e.getMessage());
            }
            collector.record(toolName, output);
            String safeOutput = output == null ? "" : output;
            memoizedResults.put(signature, safeOutput);
            return safeOutput;
        }

        private String buildSignature(String toolName, Map<String, Object> args) {
            Map<String, Object> sorted = new TreeMap<>();
            if (args != null) {
                sorted.putAll(args);
            }
            return toolName + "|" + sorted;
        }
    }

    static class ToolCallCollector {
        private final List<String> toolCalls = new ArrayList<>();
        private final List<String> toolErrors = new ArrayList<>();
        private final List<String> imageUrls = new ArrayList<>();
        private final List<String> successfulOutputs = new ArrayList<>();

        void record(String toolName, String output) {
            toolCalls.add(toolName);
            String error = detectToolError(toolName, output);
            if (!error.isBlank()) {
                toolErrors.add(error);
            } else if (output != null && !output.isBlank()) {
                successfulOutputs.add(output);
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

        boolean hasSuccessfulOutput() {
            return !successfulOutputs.isEmpty();
        }

        String lastSuccessfulOutput() {
            if (successfulOutputs.isEmpty()) {
                return "";
            }
            return successfulOutputs.get(successfulOutputs.size() - 1);
        }

        private String detectToolError(String toolName, String output) {
            if (output == null || output.isBlank()) {
                return "tool " + toolName + " returned empty output";
            }
            String normalized = output.toLowerCase(Locale.ROOT);
            boolean explicitPrefixFailure = output.startsWith("tool ")
                    || output.startsWith("网页抓取失败")
                    || output.startsWith("翻译失败")
                    || output.startsWith("天气查询失败")
                    || output.startsWith("天气预报查询失败")
                    || output.startsWith("截图失败")
                    || output.startsWith("待办提取失败")
                    || output.startsWith("摘要失败");
            boolean explicitEnglishFailure = normalized.startsWith("error")
                    || normalized.startsWith("failed")
                    || normalized.contains("permission denied")
                    || normalized.contains("certificate")
                    || normalized.contains("headless");
            boolean failed = explicitPrefixFailure || explicitEnglishFailure;
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
                                 boolean protocolUsed,
                                 String lastToolOutput) {
    }
}

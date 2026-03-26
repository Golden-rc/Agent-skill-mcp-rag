package com.eureka.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class TranslateTool implements McpTool {

    private final RestClient restClient;

    public TranslateTool() {
        this.restClient = RestClient.builder().baseUrl("https://api.mymemory.translated.net").build();
    }

    @Override
    public String name() {
        return "translate_text";
    }

    @Override
    public String description() {
        return "Translate text to target language";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of(
                                "type", "string",
                                "description", "Text to translate"
                        ),
                        "targetLang", Map.of(
                                "type", "string",
                                "description", "Target language code, e.g. zh-CN, en, ja"
                        ),
                        "sourceLang", Map.of(
                                "type", "string",
                                "description", "Source language code, default auto"
                        )
                ),
                "required", List.of("text", "targetLang")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String text = String.valueOf(arguments.getOrDefault("text", "")).trim();
            String targetLang = String.valueOf(arguments.getOrDefault("targetLang", "")).trim();
            String sourceLang = String.valueOf(arguments.getOrDefault("sourceLang", "auto")).trim();

            if (text.isBlank()) {
                return "翻译失败：text 不能为空";
            }
            if (targetLang.isBlank()) {
                return "翻译失败：targetLang 不能为空";
            }

            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String langPair = sourceLang + "|" + targetLang;

            JsonNode response = restClient.get()
                    .uri("/get?q=" + encodedText + "&langpair=" + URLEncoder.encode(langPair, StandardCharsets.UTF_8))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return "翻译失败：上游服务返回为空";
            }

            String translated = response.path("responseData").path("translatedText").asText("").trim();
            if (translated.isBlank()) {
                return "翻译失败：未获得翻译结果";
            }

            return "翻译结果\n源语言: " + sourceLang + "\n目标语言: " + targetLang + "\n结果: " + translated;
        } catch (Exception e) {
            return "翻译失败: " + (e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }
}

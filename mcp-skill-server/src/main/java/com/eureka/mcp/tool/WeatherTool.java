package com.eureka.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class WeatherTool implements McpTool {

    private final RestClient geocodingClient;
    private final RestClient forecastClient;

    public WeatherTool() {
        this.geocodingClient = RestClient.builder().baseUrl("https://geocoding-api.open-meteo.com").build();
        this.forecastClient = RestClient.builder().baseUrl("https://api.open-meteo.com").build();
    }

    @Override
    public String name() {
        return "query_weather";
    }

    @Override
    public String description() {
        return "Query real-time weather by city name";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of(
                                "type", "string",
                                "description", "City name, e.g. Beijing, Shanghai"
                        ),
                        "text", Map.of(
                                "type", "string",
                                "description", "Fallback field for natural language query"
                        )
                ),
                "required", List.of("city")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String city = resolveCity(arguments);
            if (city.isBlank()) {
                return "天气查询失败：请提供城市名（参数 city）";
            }

            CityResolved resolved = resolveCityLocation(city);
            if (resolved == null) {
                return "天气查询失败：未找到城市 " + city;
            }

            JsonNode forecast = forecastClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/forecast")
                            .queryParam("latitude", resolved.latitude())
                            .queryParam("longitude", resolved.longitude())
                            .queryParam("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
                            .queryParam("timezone", "auto")
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);

            if (forecast == null || forecast.path("current").isMissingNode()) {
                return "天气查询失败：天气服务返回为空";
            }

            JsonNode current = forecast.path("current");
            String observedAt = current.path("time").asText("-");
            String timezone = forecast.path("timezone").asText("-");
            double temp = current.path("temperature_2m").asDouble(Double.NaN);
            double feels = current.path("apparent_temperature").asDouble(Double.NaN);
            int humidity = current.path("relative_humidity_2m").asInt(-1);
            int weatherCode = current.path("weather_code").asInt(-1);
            double wind = current.path("wind_speed_10m").asDouble(Double.NaN);

            return "天气查询结果\n"
                    + "城市: " + resolved.displayName() + "\n"
                    + "观测时间: " + observedAt + " (" + timezone + ")\n"
                    + "天气: " + weatherCodeDesc(weatherCode) + " (code=" + weatherCode + ")\n"
                    + "温度: " + number(temp) + "°C，体感: " + number(feels) + "°C\n"
                    + "湿度: " + (humidity < 0 ? "-" : humidity + "%") + "\n"
                    + "风速: " + number(wind) + " km/h";
        } catch (Exception e) {
            return "天气查询失败: " + (e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    private CityResolved resolveCityLocation(String city) {
        List<String> candidates = expandCityCandidates(city);
        for (String candidate : candidates) {
            CityResolved resolved = queryGeocoding(candidate, true);
            if (resolved != null) {
                return resolved;
            }
            resolved = queryGeocoding(candidate, false);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private CityResolved queryGeocoding(String city, boolean withZhLanguage) {
        JsonNode response = geocodingClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/v1/search")
                            .queryParam("name", city)
                            .queryParam("count", "1")
                            .queryParam("format", "json");
                    if (withZhLanguage) {
                        builder.queryParam("language", "zh");
                    }
                    return builder.build();
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.path("results").isArray() || response.path("results").isEmpty()) {
            return null;
        }

        JsonNode first = response.path("results").get(0);
        return new CityResolved(
                first.path("name").asText(city),
                first.path("country").asText(""),
                first.path("latitude").asDouble(),
                first.path("longitude").asDouble()
        );
    }

    private List<String> expandCityCandidates(String city) {
        Set<String> candidates = new LinkedHashSet<>();
        String normalized = city == null ? "" : city.trim();
        if (!normalized.isBlank()) {
            candidates.add(normalized);
        }

        // 处理“上海现在天气”“北京今日气温”这类自然语言输入。
        String keyword = extractCityKeyword(normalized);
        if (!keyword.isBlank()) {
            candidates.add(keyword);
        }

        String withoutSuffix = normalized
                .replace("市", "")
                .replace("省", "")
                .replace("特别行政区", "")
                .trim();
        if (!withoutSuffix.isBlank()) {
            candidates.add(withoutSuffix);
            String keywordWithoutSuffix = extractCityKeyword(withoutSuffix);
            if (!keywordWithoutSuffix.isBlank()) {
                candidates.add(keywordWithoutSuffix);
            }
        }

        // 英文城市名兜底：仅首字母大写，避免全小写影响命中率。
        if (normalized.matches("[a-zA-Z\\s-]{2,}")) {
            candidates.add(toTitleCase(normalized));
        }

        return new ArrayList<>(candidates);
    }

    private String extractCityKeyword(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text
                .replace("现在", "")
                .replace("今日", "")
                .replace("今天", "")
                .replace("目前", "")
                .replace("天气", "")
                .replace("气温", "")
                .replace("温度", "")
                .replace("查询", "")
                .replace("请", "")
                .replace("帮我", "")
                .replace("一下", "")
                .replaceAll("[?？!！,，。:：]", " ")
                .replaceAll("(?i)\\b(weather|forecast|query|check|now|current|today|in|for|the)\\b", " ")
                .replaceAll("^(查一下|查询一下|查询|查|看一下|看看|看下|问下|帮查|帮忙查)\\s*", "")
                .trim();
        if (cleaned.isBlank()) {
            return "";
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        cleaned = cleaned.replaceAll("^[的\\s]+|[的\\s]+$", "").trim();
        if (cleaned.isBlank()) {
            return "";
        }

        // 中文城市优先返回连续汉字片段；英文保留多词城市名（如 New York）。
        String chineseOnly = cleaned.replaceAll("[^\\p{IsHan}]", "").trim();
        if (!chineseOnly.isBlank()) {
            return chineseOnly;
        }
        return cleaned;
    }

    private String toTitleCase(String input) {
        String[] words = input.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) {
                sb.append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private String resolveCity(Map<String, Object> arguments) {
        String city = String.valueOf(arguments.getOrDefault("city", "")).trim();
        if (!city.isBlank()) {
            return sanitizeCity(city);
        }
        String text = String.valueOf(arguments.getOrDefault("text", "")).trim();
        if (!text.isBlank()) {
            return sanitizeCity(text);
        }
        return "";
    }

    private String sanitizeCity(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replace("天气", "")
                .replace("查询", "")
                .replace("forecast", "")
                .replace("weather", "")
                .replace("请", "")
                .replace("帮我", "")
                .replace("一下", "")
                .trim();
        return normalized.replaceAll("[?？!！,，。:：]", "").trim();
    }

    private String number(double value) {
        if (Double.isNaN(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String weatherCodeDesc(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2 -> "晴间多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61, 63, 65 -> "雨";
            case 66, 67 -> "冻雨";
            case 71, 73, 75 -> "雪";
            case 77 -> "冰粒";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "未知";
        };
    }

    private record CityResolved(String city, String country, double latitude, double longitude) {
        private String displayName() {
            if (country == null || country.isBlank()) {
                return city;
            }
            return city + ", " + country;
        }
    }
}

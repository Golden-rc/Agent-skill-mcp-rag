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
public class WeatherForecastTool implements McpTool {

    private final RestClient geocodingClient;
    private final RestClient forecastClient;

    public WeatherForecastTool() {
        this.geocodingClient = RestClient.builder().baseUrl("https://geocoding-api.open-meteo.com").build();
        this.forecastClient = RestClient.builder().baseUrl("https://api.open-meteo.com").build();
    }

    @Override
    public String name() {
        return "query_weather_forecast";
    }

    @Override
    public String description() {
        return "Query daily weather forecast by city name";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of(
                                "type", "string",
                                "description", "City name, e.g. Shanghai, New York"
                        ),
                        "days", Map.of(
                                "type", "integer",
                                "description", "Forecast days, 1-7, default 3"
                        )
                ),
                "required", List.of("city")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String city = String.valueOf(arguments.getOrDefault("city", "")).trim();
            if (city.isBlank()) {
                return "天气预报查询失败：city 不能为空";
            }
            int days = parseDays(arguments.get("days"));

            CityResolved resolved = resolveCityLocation(city);
            if (resolved == null) {
                return "天气预报查询失败：未找到城市 " + city;
            }

            JsonNode forecast = forecastClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/forecast")
                            .queryParam("latitude", resolved.latitude())
                            .queryParam("longitude", resolved.longitude())
                            .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                            .queryParam("forecast_days", days)
                            .queryParam("timezone", "auto")
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);

            if (forecast == null || forecast.path("daily").isMissingNode()) {
                return "天气预报查询失败：天气服务返回为空";
            }

            JsonNode daily = forecast.path("daily");
            JsonNode times = daily.path("time");
            JsonNode codes = daily.path("weather_code");
            JsonNode maxTemps = daily.path("temperature_2m_max");
            JsonNode minTemps = daily.path("temperature_2m_min");
            JsonNode rains = daily.path("precipitation_probability_max");

            StringBuilder sb = new StringBuilder();
            sb.append("天气预报结果\n")
                    .append("城市: ").append(resolved.displayName()).append("\n")
                    .append("时区: ").append(forecast.path("timezone").asText("-")).append("\n");

            int size = Math.min(days, times.size());
            for (int i = 0; i < size; i++) {
                int code = codes.path(i).asInt(-1);
                sb.append(times.path(i).asText("-"))
                        .append(" | ").append(weatherCodeDesc(code))
                        .append(" | ").append(number(minTemps.path(i).asDouble(Double.NaN))).append("~")
                        .append(number(maxTemps.path(i).asDouble(Double.NaN))).append("°C")
                        .append(" | 降水概率: ").append(rains.path(i).isMissingNode() ? "-" : rains.path(i).asText("-")).append("%")
                        .append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "天气预报查询失败: " + (e.getMessage() == null ? "unknown" : e.getMessage());
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

        String cleaned = normalized
                .replace("天气", "")
                .replace("预报", "")
                .replace("今天", "")
                .replace("明天", "")
                .replace("未来", "")
                .replace("查询", "")
                .replace("请", "")
                .replace("帮我", "")
                .replaceAll("[?？!！,，。:：]", " ")
                .trim();
        if (!cleaned.isBlank()) {
            candidates.add(cleaned);
        }

        String withoutSuffix = cleaned
                .replace("市", "")
                .replace("省", "")
                .replace("特别行政区", "")
                .trim();
        if (!withoutSuffix.isBlank()) {
            candidates.add(withoutSuffix);
        }

        if (cleaned.matches("[a-zA-Z\\s-]{2,}")) {
            candidates.add(toTitleCase(cleaned));
        }
        return new ArrayList<>(candidates);
    }

    private String toTitleCase(String input) {
        String[] words = input.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
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

    private int parseDays(Object value) {
        if (value == null) {
            return 3;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return Math.max(1, Math.min(parsed, 7));
        } catch (Exception ignored) {
            return 3;
        }
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

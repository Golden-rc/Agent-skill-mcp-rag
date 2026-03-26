package com.eureka.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TableExtractorTool implements McpTool {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "table_extractor";
    }

    @Override
    public String description() {
        return "Extract first markdown/html table to CSV or JSON";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of("type", "string", "description", "Text that contains markdown/html table"),
                        "format", Map.of("type", "string", "description", "csv|json, default csv")
                ),
                "required", List.of("text")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String text = String.valueOf(arguments.getOrDefault("text", ""));
            if (text.isBlank()) {
                return "表格提取失败: text 不能为空";
            }
            String format = String.valueOf(arguments.getOrDefault("format", "csv")).trim().toLowerCase();
            List<List<String>> rows = extractMarkdownTable(text);
            if (rows.isEmpty()) {
                rows = extractHtmlTable(text);
            }
            if (rows.isEmpty() || rows.size() < 2) {
                return "表格提取失败: 未找到有效表格";
            }
            return "json".equals(format) ? toJson(rows) : toCsv(rows);
        } catch (Exception e) {
            return "表格提取失败: " + safeMessage(e);
        }
    }

    private List<List<String>> extractMarkdownTable(String text) {
        String[] lines = text.split("\\r?\\n");
        List<List<String>> block = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) {
                if (!block.isEmpty()) {
                    break;
                }
                continue;
            }
            List<String> row = new ArrayList<>();
            String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|");
            for (String cell : cells) {
                row.add(cell.trim());
            }
            block.add(row);
        }
        if (block.size() >= 2 && block.get(1).stream().allMatch(c -> c.matches("[:\\- ]+"))) {
            block.remove(1);
        }
        return block;
    }

    private List<List<String>> extractHtmlTable(String text) {
        List<List<String>> rows = new ArrayList<>();
        String table = text.replaceAll("(?is).*?<table[^>]*>", "")
                .replaceAll("(?is)</table>.*", "");
        if (table.equals(text)) {
            return rows;
        }
        String[] trParts = table.split("(?is)</tr>");
        for (String tr : trParts) {
            String rowRaw = tr.replaceAll("(?is).*?<tr[^>]*>", "");
            if (rowRaw.equals(tr)) {
                continue;
            }
            String[] cells = rowRaw.split("(?is)</t[hd]>");
            List<String> row = new ArrayList<>();
            for (String cell : cells) {
                String value = cell.replaceAll("(?is).*?<t[hd][^>]*>", "")
                        .replaceAll("(?is)<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
                if (!value.isBlank()) {
                    row.add(value);
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private String toCsv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("表格提取结果(CSV)\n");
        for (List<String> row : rows) {
            List<String> escaped = row.stream().map(this::csvEscape).toList();
            sb.append(String.join(",", escaped)).append("\n");
        }
        return sb.toString().trim();
    }

    private String toJson(List<List<String>> rows) throws Exception {
        List<String> header = rows.get(0);
        List<Map<String, String>> data = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            Map<String, String> item = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                String key = header.get(c);
                String value = c < row.size() ? row.get(c) : "";
                item.put(key, value);
            }
            data.add(item);
        }
        return "表格提取结果(JSON)\n" + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
    }

    private String csvEscape(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "unknown" : message.replaceAll("\\s+", " ").trim();
    }
}

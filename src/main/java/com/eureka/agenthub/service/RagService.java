package com.eureka.agenthub.service;

import com.eureka.agenthub.model.RagHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagService {

    private final JdbcTemplate jdbcTemplate;
    private final ModelClientService modelClientService;

    public RagService(JdbcTemplate jdbcTemplate, ModelClientService modelClientService) {
        this.jdbcTemplate = jdbcTemplate;
        this.modelClientService = modelClientService;
    }

    public int ingest(String source, String text) {
        List<String> chunks = splitText(text, 500);
        int inserted = 0;
        for (String chunk : chunks) {
            if (!StringUtils.hasText(chunk)) {
                continue;
            }
            String vector = toVectorLiteral(modelClientService.embed(chunk));
            inserted += jdbcTemplate.update(
                    "INSERT INTO rag_chunks(source, content, embedding) VALUES (?, ?, ?::vector)",
                    source,
                    chunk,
                    vector
            );
        }
        return inserted;
    }

    public List<RagHit> retrieve(String query, int topK) {
        String vector = toVectorLiteral(modelClientService.embed(query));
        jdbcTemplate.execute("SET ivfflat.probes = 100");
        List<RagHit> hits = jdbcTemplate.query(
                "SELECT source, content, 1 - (embedding <=> ?::vector) AS score " +
                        "FROM rag_chunks ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, rowNum) -> new RagHit(
                        rs.getString("source"),
                        rs.getString("content"),
                        rs.getDouble("score")
                ),
                vector,
                vector,
                topK
        );
        if (!hits.isEmpty()) {
            return hits;
        }

        jdbcTemplate.execute("SET enable_indexscan = off");
        List<RagHit> fallbackHits = jdbcTemplate.query(
                "SELECT source, content, 1 - (embedding <=> ?::vector) AS score " +
                        "FROM rag_chunks ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, rowNum) -> new RagHit(
                        rs.getString("source"),
                        rs.getString("content"),
                        rs.getDouble("score")
                ),
                vector,
                vector,
                topK
        );
        jdbcTemplate.execute("SET enable_indexscan = on");
        return fallbackHits;
    }

    private List<String> splitText(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").trim();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + maxChunkSize, normalized.length());
            chunks.add(normalized.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private String toVectorLiteral(List<Double> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding.get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}

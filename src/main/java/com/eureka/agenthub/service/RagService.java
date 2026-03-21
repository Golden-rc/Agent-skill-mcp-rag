package com.eureka.agenthub.service;

import com.eureka.agenthub.config.AppProperties;
import com.eureka.agenthub.model.RagHit;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
/**
 * RAG 数据服务。
 * <p>
 * 提供写入、检索、后台管理（查/改/删 chunk）能力。
 */
public class RagService {

    private final JdbcTemplate jdbcTemplate;
    private final ModelClientService modelClientService;
    private final AppProperties appProperties;

    public RagService(JdbcTemplate jdbcTemplate,
                      ModelClientService modelClientService,
                      AppProperties appProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.modelClientService = modelClientService;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void validateEmbeddingDimension() {
        int configuredDimension = appProperties.getRag().getEmbeddingDimension();
        if (configuredDimension <= 0) {
            throw new IllegalStateException("app.rag.embedding-dimension must be positive");
        }
        Integer existingDimension = jdbcTemplate.query(
                        "SELECT vector_dims(embedding) FROM rag_chunks LIMIT 1",
                        rs -> rs.next() ? rs.getInt(1) : null
                );
        if (existingDimension != null && existingDimension != configuredDimension) {
            throw new IllegalStateException("rag embedding dimension mismatch, db="
                    + existingDimension + " config=" + configuredDimension
                    + ". Please rebuild rag_chunks with new embedding model.");
        }
    }

    public int ingest(String source, String text) {
        // 简单按定长切块，保证单块 token 大小可控。
        int chunkSize = Math.max(100, appProperties.getRag().getChunkSize());
        int chunkOverlap = Math.max(0, appProperties.getRag().getChunkOverlap());
        List<String> chunks = splitText(text, chunkSize, chunkOverlap);
        int inserted = 0;
        for (String chunk : chunks) {
            if (!StringUtils.hasText(chunk)) {
                continue;
            }
            String vector = toVectorLiteral(modelClientService.embed(chunk));
            // 将 source、content 与 embedding 同时写入，便于后续溯源。
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
        int configuredTopK = Math.max(1, appProperties.getRag().getRetrieveTopK());
        int effectiveTopK = Math.max(topK, configuredTopK);
        String vector = toVectorLiteral(modelClientService.embed(query));
        // 提高 ivfflat 召回质量，避免小数据量下漏召回。
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
                effectiveTopK
        );
        if (!hits.isEmpty()) {
            return hits;
        }

        // 兜底：关闭 indexscan 做顺序扫描，尽量保证能查到结果。
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
                effectiveTopK
        );
        jdbcTemplate.execute("SET enable_indexscan = on");
        return fallbackHits;
    }

    public List<RagChunkRow> listChunks(String source, int limit) {
        return listChunks(source, limit, 0);
    }

    public List<RagChunkRow> listChunks(String source, int limit, int offset) {
        // 限制查询上限，避免前端一次拉取过大数据。
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        if (StringUtils.hasText(source)) {
            return jdbcTemplate.query(
                    "SELECT id, source, content, created_at FROM rag_chunks WHERE source = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> new RagChunkRow(
                            rs.getLong("id"),
                            rs.getString("source"),
                            rs.getString("content"),
                            Objects.toString(rs.getTimestamp("created_at"), "")
                    ),
                    source,
                    safeLimit,
                    safeOffset
            );
        }

        return jdbcTemplate.query(
                "SELECT id, source, content, created_at FROM rag_chunks ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new RagChunkRow(
                        rs.getLong("id"),
                        rs.getString("source"),
                        rs.getString("content"),
                        Objects.toString(rs.getTimestamp("created_at"), "")
                ),
                safeLimit,
                safeOffset
        );
    }

    public long countChunks(String source) {
        if (StringUtils.hasText(source)) {
            Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_chunks WHERE source = ?", Long.class, source);
            return total == null ? 0 : total;
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_chunks", Long.class);
        return total == null ? 0 : total;
    }

    public List<String> listSources() {
        return jdbcTemplate.query(
                "SELECT DISTINCT source FROM rag_chunks ORDER BY source ASC",
                (rs, rowNum) -> rs.getString("source")
        );
    }

    public int deleteChunk(long id) {
        return jdbcTemplate.update("DELETE FROM rag_chunks WHERE id = ?", id);
    }

    public int updateChunk(long id, String source, String content) {
        // 内容更新后必须重算 embedding，否则检索语义会失真。
        String vector = toVectorLiteral(modelClientService.embed(content));
        return jdbcTemplate.update(
                "UPDATE rag_chunks SET source = ?, content = ?, embedding = ?::vector WHERE id = ?",
                source,
                content,
                vector,
                id
        );
    }

    /**
     * RAG 数据库行结构，用于管理接口返回。
     */
    public record RagChunkRow(long id, String source, String content, String createdAt) {
    }

    private List<String> splitText(String text, int maxChunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return chunks;
        }
        int safeOverlap = Math.min(Math.max(0, overlap), Math.max(0, maxChunkSize - 1));
        int step = Math.max(1, maxChunkSize - safeOverlap);
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + maxChunkSize, normalized.length());
            chunks.add(normalized.substring(start, end));
            start += step;
        }
        return chunks;
    }

    private String toVectorLiteral(List<Double> embedding) {
        // pgvector 插入格式: [0.1,0.2,...]
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

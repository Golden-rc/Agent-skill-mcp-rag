package com.eureka.agenthub.model;

/**
 * /rag/ingest 响应体。
 */
public record IngestResponse(String source, int chunksInserted) {
}

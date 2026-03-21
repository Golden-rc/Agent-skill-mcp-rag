package com.eureka.agenthub.model;

/**
 * RAG 检索命中项。
 */
public record RagHit(String source, String content, double score) {
}

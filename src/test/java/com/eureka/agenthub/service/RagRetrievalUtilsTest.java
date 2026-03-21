package com.eureka.agenthub.service;

import com.eureka.agenthub.model.RagHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalUtilsTest {

    @Test
    void shouldSplitRecursivelyWithOverlap() {
        String text = "第一段介绍RAG系统。\n\n第二段介绍检索和重排序。\n\n第三段介绍评估方法。";
        List<String> chunks = RagRetrievalUtils.splitRecursively(text, 20, 8, 5);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(1).contains("排序") || chunks.get(1).contains("检索"));
    }

    @Test
    void shouldRerankBySemanticAndKeywordSignals() {
        List<RagHit> candidates = List.of(
                new RagHit("a", "这是一个完全无关的段落", 0.90),
                new RagHit("b", "RAG 检索阶段会先召回再重排序，提高 top-k 准确性", 0.82),
                new RagHit("c", "总结：构建知识库", 0.65)
        );

        List<RagHit> ranked = RagRetrievalUtils.rerank("RAG 重排序 top-k", candidates, 2);

        assertEquals(2, ranked.size());
        assertEquals("b", ranked.get(0).source());
        assertTrue(ranked.get(0).score() >= ranked.get(1).score());
    }
}

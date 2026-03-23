package com.eureka.agenthub.service;

import com.eureka.agenthub.model.RagHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagContextPackerTest {

    @Test
    void shouldDeduplicateAndRespectBudgets() {
        List<RagHit> hits = List.of(
                new RagHit("a", "RAG 设计原则：先召回后精排。", 0.91),
                new RagHit("a", "RAG 设计原则：先召回后精排。\n\n", 0.90),
                new RagHit("b", "这是另一段知识，用于补充说明工程化实践。", 0.85)
        );

        List<RagHit> packed = RagContextPacker.pack(hits, 60, 30);

        // 第2条应被去重，剩余命中不超过预算上限。
        assertEquals(2, packed.size());
        assertTrue(packed.get(0).content().length() <= 33);
        assertTrue(packed.get(1).content().length() <= 33);
    }
}

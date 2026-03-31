package com.eureka.agenthub.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamMetricsServiceTest {

    @Test
    void shouldAggregateCompletedCancelledAndErroredMetrics() {
        StreamMetricsService service = new StreamMetricsService();

        service.onStart();
        service.onCompleted(120, 80, 1, 1);

        service.onStart();
        service.onCancelled(50, 20, 0, 0);

        service.onStart();
        service.onError(30, "boom", 10, 1, 0);

        Map<String, Object> snapshot = service.snapshot();

        assertEquals(3L, snapshot.get("totalRequests"));
        assertEquals(0L, snapshot.get("activeRequests"));
        assertEquals(1L, snapshot.get("completedRequests"));
        assertEquals(1L, snapshot.get("cancelledRequests"));
        assertEquals(1L, snapshot.get("erroredRequests"));
        assertEquals(110L, snapshot.get("totalTokenChars"));
        assertEquals("boom", snapshot.get("lastError"));
    }
}

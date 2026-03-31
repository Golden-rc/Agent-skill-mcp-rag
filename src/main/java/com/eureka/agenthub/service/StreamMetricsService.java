package com.eureka.agenthub.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * SSE 流式对话运行指标。
 * <p>
 * 记录请求总量、完成/取消/异常、活动连接、token 字符量、工具事件数量和平均耗时。
 */
@Service
public class StreamMetricsService {

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder activeRequests = new LongAdder();
    private final LongAdder completedRequests = new LongAdder();
    private final LongAdder cancelledRequests = new LongAdder();
    private final LongAdder erroredRequests = new LongAdder();

    private final LongAdder totalDurationMs = new LongAdder();
    private final LongAdder totalTokenChars = new LongAdder();
    private final LongAdder totalToolStartEvents = new LongAdder();
    private final LongAdder totalToolDoneEvents = new LongAdder();

    private final AtomicLong lastUpdatedAt = new AtomicLong(0L);
    private volatile String lastError = "";

    public void onStart() {
        totalRequests.increment();
        activeRequests.increment();
        touch();
    }

    public void onCompleted(long durationMs, int tokenChars, int toolStartEvents, int toolDoneEvents) {
        completedRequests.increment();
        activeRequests.decrement();
        totalDurationMs.add(Math.max(0L, durationMs));
        totalTokenChars.add(Math.max(0, tokenChars));
        totalToolStartEvents.add(Math.max(0, toolStartEvents));
        totalToolDoneEvents.add(Math.max(0, toolDoneEvents));
        touch();
    }

    public void onCancelled(long durationMs, int tokenChars, int toolStartEvents, int toolDoneEvents) {
        cancelledRequests.increment();
        activeRequests.decrement();
        totalDurationMs.add(Math.max(0L, durationMs));
        totalTokenChars.add(Math.max(0, tokenChars));
        totalToolStartEvents.add(Math.max(0, toolStartEvents));
        totalToolDoneEvents.add(Math.max(0, toolDoneEvents));
        touch();
    }

    public void onError(long durationMs, String error, int tokenChars, int toolStartEvents, int toolDoneEvents) {
        erroredRequests.increment();
        activeRequests.decrement();
        totalDurationMs.add(Math.max(0L, durationMs));
        totalTokenChars.add(Math.max(0, tokenChars));
        totalToolStartEvents.add(Math.max(0, toolStartEvents));
        totalToolDoneEvents.add(Math.max(0, toolDoneEvents));
        lastError = error == null ? "" : error;
        touch();
    }

    public Map<String, Object> snapshot() {
        long total = totalRequests.sum();
        long avgDuration = total == 0 ? 0 : totalDurationMs.sum() / total;
        long avgTokenChars = total == 0 ? 0 : totalTokenChars.sum() / total;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRequests", total);
        result.put("activeRequests", activeRequests.sum());
        result.put("completedRequests", completedRequests.sum());
        result.put("cancelledRequests", cancelledRequests.sum());
        result.put("erroredRequests", erroredRequests.sum());
        result.put("totalDurationMs", totalDurationMs.sum());
        result.put("avgDurationMs", avgDuration);
        result.put("totalTokenChars", totalTokenChars.sum());
        result.put("avgTokenChars", avgTokenChars);
        result.put("totalToolStartEvents", totalToolStartEvents.sum());
        result.put("totalToolDoneEvents", totalToolDoneEvents.sum());
        result.put("lastError", lastError);
        result.put("lastUpdatedAt", lastUpdatedAt.get());
        return result;
    }

    private void touch() {
        lastUpdatedAt.set(System.currentTimeMillis());
    }
}

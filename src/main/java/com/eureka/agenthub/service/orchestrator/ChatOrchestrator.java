package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.SseEvent;

import java.util.function.Consumer;

/**
 * 对话编排器抽象。
 */
public interface ChatOrchestrator {

    /**
     * 编排器标识：classic / agent。
     */
    String key();

    ChatResponse chat(ChatRequest request, String provider);

    /**
     * SSE 流式版本，通过 emitter 实时推送进度/token/工具事件，最后推送 done 事件。
     * 默认实现：同步执行后直接推送 done；子类可覆盖以获得真正流式效果。
     */
    default void stream(ChatRequest request, String provider, Consumer<SseEvent> emitter) {
        ChatResponse response = chat(request, provider);
        emitter.accept(SseEvent.done(response));
    }
}

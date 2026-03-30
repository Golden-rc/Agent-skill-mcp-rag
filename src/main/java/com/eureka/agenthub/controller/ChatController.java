package com.eureka.agenthub.controller;

import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.SseEvent;
import com.eureka.agenthub.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天接口入口。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            4,
            24,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            namedThreadFactory("chat-sse-worker"),
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final ScheduledExecutorService heartbeatExecutor = java.util.concurrent.Executors.newScheduledThreadPool(
            1,
            namedThreadFactory("chat-sse-heartbeat")
    );

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(request));
    }

    /**
     * SSE 流式聊天接口。
     * 前端使用 fetch + ReadableStream 接收，事件格式为 text/event-stream。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean closed = new AtomicBoolean(false);

        Future<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                // 保活事件，避免代理层在长时生成阶段提前断开连接。
                emitter.send(SseEmitter.event().name("ping").data("{}"));
            } catch (Exception e) {
                closed.set(true);
                emitter.completeWithError(e);
            }
        }, 15, 15, TimeUnit.SECONDS);

        Future<?> job = sseExecutor.submit(() -> {
            try {
                chatService.stream(request, event -> {
                    if (closed.get()) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
                    } catch (Exception e) {
                        closed.set(true);
                        emitter.completeWithError(e);
                    }
                });
                closed.set(true);
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(SseEvent.error(e.getMessage()))));
                } catch (Exception ignored) {
                }
                closed.set(true);
                emitter.completeWithError(e);
            } finally {
                heartbeat.cancel(true);
            }
        });

        emitter.onTimeout(() -> {
            closed.set(true);
            job.cancel(true);
            heartbeat.cancel(true);
            emitter.complete();
        });
        emitter.onError(error -> {
            closed.set(true);
            job.cancel(true);
            heartbeat.cancel(true);
        });
        emitter.onCompletion(() -> {
            closed.set(true);
            job.cancel(true);
            heartbeat.cancel(true);
        });

        return emitter;
    }

    @PreDestroy
    public void shutdownExecutors() {
        heartbeatExecutor.shutdownNow();
        sseExecutor.shutdownNow();
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        return new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, prefix + "-" + idx.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}

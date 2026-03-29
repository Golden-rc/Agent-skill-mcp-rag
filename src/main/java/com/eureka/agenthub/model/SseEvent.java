package com.eureka.agenthub.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 推送事件。
 * <ul>
 *   <li>status   - 进度状态，字段 msg</li>
 *   <li>token    - LLM 流式 token，字段 text</li>
 *   <li>tool_start - 工具开始调用，字段 name</li>
 *   <li>tool_done  - 工具调用完成，字段 name + text（输出摘要）</li>
 *   <li>done     - 全部完成，字段 data（完整 ChatResponse）</li>
 *   <li>error    - 错误，字段 msg</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SseEvent(String type, String msg, String text, String name, ChatResponse data) {

    public static SseEvent status(String msg) {
        return new SseEvent("status", msg, null, null, null);
    }

    public static SseEvent token(String text) {
        return new SseEvent("token", null, text, null, null);
    }

    public static SseEvent toolStart(String name) {
        return new SseEvent("tool_start", null, null, name, null);
    }

    public static SseEvent toolDone(String name, String snippet) {
        return new SseEvent("tool_done", null, snippet, name, null);
    }

    public static SseEvent done(ChatResponse data) {
        return new SseEvent("done", null, null, null, data);
    }

    public static SseEvent error(String msg) {
        return new SseEvent("error", msg, null, null, null);
    }
}

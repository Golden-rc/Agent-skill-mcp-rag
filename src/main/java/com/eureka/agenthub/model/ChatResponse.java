package com.eureka.agenthub.model;

import java.util.List;

/**
 * /chat 响应体。
 */
public record ChatResponse(String answer,
                           // 最终使用的模型提供方。
                           String providerUsed,
                           // RAG 引用片段。
                           List<RagHit> citations,
                           // 本次触发的 MCP 工具名。
                           List<String> toolCalls,
                           // 工具产出的可展示图片 URL。
                           List<String> imageUrls,
                           // 本次实际回答模式：direct / rag。
                           String modeUsed,
                           // 模式决策来源，便于前端调试。
                           String modeReason) {
}

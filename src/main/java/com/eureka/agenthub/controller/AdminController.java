package com.eureka.agenthub.controller;

import com.eureka.agenthub.service.MemoryService;
import com.eureka.agenthub.service.RagService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/admin")
/**
 * 运维/管理接口。
 * <p>
 * 提供 RAG Chunk 的查看、修改、删除，以及 Session 的查询和清理。
 */
public class AdminController {

    private final RagService ragService;
    private final MemoryService memoryService;

    public AdminController(RagService ragService, MemoryService memoryService) {
        this.ragService = ragService;
        this.memoryService = memoryService;
    }

    @GetMapping("/rag/chunks")
    /**
     * 查询 RAG 数据表中的分块记录。
     */
    public ResponseEntity<List<RagService.RagChunkRow>> listChunks(
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ragService.listChunks(source, limit));
    }

    @PutMapping("/rag/chunks/{id}")
    /**
     * 更新指定 chunk 的 source/content（同时刷新 embedding）。
     */
    public ResponseEntity<Map<String, Object>> updateChunk(@PathVariable long id,
                                                            @RequestBody UpdateChunkRequest request) {
        int updated = ragService.updateChunk(id, request.source(), request.content());
        if (updated == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "chunk not found"));
        }
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @DeleteMapping("/rag/chunks/{id}")
    /**
     * 删除指定 chunk。
     */
    public ResponseEntity<Map<String, Object>> deleteChunk(@PathVariable long id) {
        int deleted = ragService.deleteChunk(id);
        if (deleted == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "chunk not found"));
        }
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @GetMapping("/sessions")
    /**
     * 列出当前 Redis 会话键和消息统计。
     */
    public ResponseEntity<List<MemoryService.SessionInfo>> listSessions() {
        return ResponseEntity.ok(memoryService.listSessions());
    }

    @GetMapping("/sessions/{sessionId}")
    /**
     * 查询指定会话历史消息。
     */
    public ResponseEntity<List<?>> sessionHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(memoryService.loadHistory(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    /**
     * 清空指定会话。
     */
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        int deleted = memoryService.clearSession(sessionId);
        return ResponseEntity.ok(Map.of("cleared", deleted));
    }

    /**
     * 前端编辑 chunk 时的请求体。
     */
    public record UpdateChunkRequest(@NotBlank String source, @NotBlank String content) {
    }
}

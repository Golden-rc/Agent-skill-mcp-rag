package com.eureka.agenthub.controller;

import com.eureka.agenthub.model.ChatRequest;
import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
/**
 * 聊天接口入口。
 */
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    /**
     * 统一聊天入口。
     */
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(request));
    }
}

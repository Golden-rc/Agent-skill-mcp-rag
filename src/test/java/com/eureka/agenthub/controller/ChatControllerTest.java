package com.eureka.agenthub.controller;

import com.eureka.agenthub.model.ChatResponse;
import com.eureka.agenthub.model.RagHit;
import com.eureka.agenthub.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @Test
    void shouldReturnChatResponse() throws Exception {
        ChatResponse response = new ChatResponse(
                "ok",
                "openai",
                List.of(new RagHit("kb", "doc chunk", 0.9)),
                List.of("extract_todos")
        );
        when(chatService.chat(any())).thenReturn(response);

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId":"s1",
                                  "provider":"openai",
                                  "message":"hello"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("ok"))
                .andExpect(jsonPath("$.providerUsed").value("openai"))
                .andExpect(jsonPath("$.citations[0].source").value("kb"))
                .andExpect(jsonPath("$.toolCalls[0]").value("extract_todos"));
    }

    @Test
    void shouldRejectInvalidRequest() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId":"",
                                  "message":""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request validation failed"));
    }
}

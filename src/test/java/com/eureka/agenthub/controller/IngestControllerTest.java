package com.eureka.agenthub.controller;

import com.eureka.agenthub.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestController.class)
class IngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagService ragService;

    @Test
    void shouldIngestTextSuccessfully() throws Exception {
        when(ragService.ingest(eq("internship-guide"), eq("demo text"))).thenReturn(2);

        mockMvc.perform(post("/rag/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source":"internship-guide",
                                  "text":"demo text"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("internship-guide"))
                .andExpect(jsonPath("$.chunksInserted").value(2));
    }

    @Test
    void shouldRejectInvalidIngestRequest() throws Exception {
        mockMvc.perform(post("/rag/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source":"",
                                  "text":""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request validation failed"));
    }
}

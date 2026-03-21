package com.eureka.agenthub.controller;

import com.eureka.agenthub.service.RagService;
import com.eureka.agenthub.service.DocumentExtractService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestController.class)
/**
 * IngestController Web 层测试。
 */
class IngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagService ragService;

    @MockBean
    private DocumentExtractService documentExtractService;

    @Test
    void shouldIngestTextSuccessfully() throws Exception {
        // 模拟写入 2 个 chunk。
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
        // 空 source/text 应触发参数校验错误。
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

    @Test
    void shouldReturnBadRequestWhenEmbeddingModelInvalid() throws Exception {
        doThrow(new IllegalArgumentException("embedding model not found: text-embedding-3-small"))
                .when(ragService)
                .ingest(eq("internship-guide"), eq("demo text"));

        mockMvc.perform(post("/rag/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source":"internship-guide",
                                  "text":"demo text"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("embedding model not found: text-embedding-3-small"));
    }

    @Test
    void shouldIngestFilesBatchSuccessfully() throws Exception {
        MockMultipartFile f1 = new MockMultipartFile("files", "a.txt", "text/plain", "aaa".getBytes());
        MockMultipartFile f2 = new MockMultipartFile("files", "b.txt", "text/plain", "bbb".getBytes());

        when(documentExtractService.extractText(any())).thenReturn("doc text");
        when(ragService.ingest(eq("batch/a.txt"), eq("doc text"))).thenReturn(2);
        when(ragService.ingest(eq("batch/b.txt"), eq("doc text"))).thenReturn(3);

        mockMvc.perform(multipart("/rag/ingest/files")
                        .file(f1)
                        .file(f2)
                        .param("sourcePrefix", "batch")
                        .param("continueOnError", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.success").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.totalChunks").value(5));
    }

    @Test
    void shouldReturnPartialSuccessForBatchIngest() throws Exception {
        MockMultipartFile f1 = new MockMultipartFile("files", "a.txt", "text/plain", "aaa".getBytes());
        MockMultipartFile f2 = new MockMultipartFile("files", "b.txt", "text/plain", "bbb".getBytes());

        when(documentExtractService.extractText(any())).thenReturn("doc text");
        when(ragService.ingest(eq("batch/a.txt"), eq("doc text"))).thenReturn(2);
        doThrow(new IllegalArgumentException("unsupported or invalid document format"))
                .when(ragService)
                .ingest(eq("batch/b.txt"), eq("doc text"));

        mockMvc.perform(multipart("/rag/ingest/files")
                        .file(f1)
                        .file(f2)
                        .param("sourcePrefix", "batch")
                        .param("continueOnError", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.success").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[1].status").value("failed"));
    }
}

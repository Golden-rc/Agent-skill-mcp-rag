package com.eureka.agenthub.controller;

import com.eureka.agenthub.model.IngestRequest;
import com.eureka.agenthub.model.IngestResponse;
import com.eureka.agenthub.service.DocumentExtractService;
import com.eureka.agenthub.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/rag")
/**
 * RAG 数据写入入口。
 */
public class IngestController {

    private final RagService ragService;
    private final DocumentExtractService documentExtractService;

    public IngestController(RagService ragService, DocumentExtractService documentExtractService) {
        this.ragService = ragService;
        this.documentExtractService = documentExtractService;
    }

    @PostMapping("/ingest")
    /**
     * 将文本切块并写入向量库。
     */
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        int inserted = ragService.ingest(request.getSource(), request.getText());
        return ResponseEntity.ok(new IngestResponse(request.getSource(), inserted));
    }

    /**
     * 上传文件并写入向量库。
     */
    @PostMapping("/ingest/file")
    public ResponseEntity<IngestResponse> ingestFile(@RequestParam("file") MultipartFile file,
                                                     @RequestParam(value = "source", required = false) String source) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("uploaded file is empty");
        }

        String safeSource = (source == null || source.isBlank()) ? file.getOriginalFilename() : source;
        if (safeSource == null || safeSource.isBlank()) {
            safeSource = "uploaded-file";
        }

        String text = documentExtractService.extractText(file);
        int inserted = ragService.ingest(safeSource, text);
        return ResponseEntity.ok(new IngestResponse(safeSource, inserted));
    }
}

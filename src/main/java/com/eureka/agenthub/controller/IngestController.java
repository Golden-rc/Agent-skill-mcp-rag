package com.eureka.agenthub.controller;

import com.eureka.agenthub.model.IngestBatchResponse;
import com.eureka.agenthub.model.IngestFileResult;
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

import java.util.ArrayList;
import java.util.List;

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

    /**
     * 批量上传文件并写入向量库。
     */
    @PostMapping("/ingest/files")
    public ResponseEntity<IngestBatchResponse> ingestFiles(@RequestParam("files") MultipartFile[] files,
                                                           @RequestParam(value = "sourcePrefix", required = false) String sourcePrefix,
                                                           @RequestParam(value = "continueOnError", defaultValue = "true") boolean continueOnError) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("files are required");
        }

        List<IngestFileResult> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int totalChunks = 0;

        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            String fileName = file == null ? "" : String.valueOf(file.getOriginalFilename());
            String source = buildSource(fileName, sourcePrefix, i + 1);

            try {
                if (file == null || file.isEmpty()) {
                    throw new IllegalArgumentException("uploaded file is empty");
                }
                String text = documentExtractService.extractText(file);
                int inserted = ragService.ingest(source, text);
                success++;
                totalChunks += inserted;
                results.add(new IngestFileResult(fileName, source, "success", inserted, ""));
            } catch (Exception ex) {
                failed++;
                String error = ex.getMessage() == null ? "ingest failed" : ex.getMessage();
                results.add(new IngestFileResult(fileName, source, "failed", 0, error));
                if (!continueOnError) {
                    throw new IllegalArgumentException("failed on file " + source + ": " + error);
                }
            }
        }

        return ResponseEntity.ok(new IngestBatchResponse(files.length, success, failed, totalChunks, results));
    }

    private String buildSource(String fileName, String sourcePrefix, int index) {
        String safeFileName = (fileName == null || fileName.isBlank()) ? ("uploaded-file-" + index) : fileName;
        if (sourcePrefix == null || sourcePrefix.isBlank()) {
            return safeFileName;
        }
        return sourcePrefix.trim() + "/" + safeFileName;
    }
}

package com.eureka.agenthub.controller;

import com.eureka.agenthub.model.IngestRequest;
import com.eureka.agenthub.model.IngestResponse;
import com.eureka.agenthub.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag")
public class IngestController {

    private final RagService ragService;

    public IngestController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        int inserted = ragService.ingest(request.getSource(), request.getText());
        return ResponseEntity.ok(new IngestResponse(request.getSource(), inserted));
    }
}

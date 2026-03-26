package com.eureka.agenthub.adapter;

import com.eureka.agenthub.model.RagHit;
import com.eureka.agenthub.port.RetrieverPort;
import com.eureka.agenthub.service.RagService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagRetrieverAdapter implements RetrieverPort {

    private final RagService ragService;

    public RagRetrieverAdapter(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public List<RagHit> retrieve(String query, int topK) {
        return ragService.retrieve(query, topK);
    }
}

package com.eureka.agenthub.model;

import java.util.List;

public record ChatResponse(String answer,
                           String providerUsed,
                           List<RagHit> citations,
                           List<String> toolCalls) {
}

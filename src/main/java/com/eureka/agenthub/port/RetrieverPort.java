package com.eureka.agenthub.port;

import com.eureka.agenthub.model.RagHit;

import java.util.List;

/**
 * 检索能力抽象。
 */
public interface RetrieverPort {

    List<RagHit> retrieve(String query, int topK);
}

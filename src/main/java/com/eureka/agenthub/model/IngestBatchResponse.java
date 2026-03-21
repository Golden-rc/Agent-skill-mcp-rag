package com.eureka.agenthub.model;

import java.util.List;

/**
 * /rag/ingest/files 响应体。
 */
public record IngestBatchResponse(int total,
                                  int success,
                                  int failed,
                                  int totalChunks,
                                  List<IngestFileResult> results) {
}

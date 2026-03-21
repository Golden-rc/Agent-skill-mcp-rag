package com.eureka.agenthub.model;

/**
 * 批量文件写入单项结果。
 */
public record IngestFileResult(String fileName,
                               String source,
                               String status,
                               int chunksInserted,
                               String error) {
}

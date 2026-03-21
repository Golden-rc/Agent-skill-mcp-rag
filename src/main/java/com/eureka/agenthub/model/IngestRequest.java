package com.eureka.agenthub.model;

import jakarta.validation.constraints.NotBlank;

/**
 * /rag/ingest 请求体。
 */
public class IngestRequest {
    @NotBlank
    /** 文本来源标签，便于引用和管理。 */
    private String source;
    @NotBlank
    /** 待切块并向量化的原始文本。 */
    private String text;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}

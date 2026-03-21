package com.eureka.agenthub.model;

import jakarta.validation.constraints.NotBlank;

public class IngestRequest {
    @NotBlank
    private String source;
    @NotBlank
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

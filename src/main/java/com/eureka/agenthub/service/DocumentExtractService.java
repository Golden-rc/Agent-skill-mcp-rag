package com.eureka.agenthub.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文档解析服务。
 * <p>
 * 使用 Apache Tika 从上传文件中提取纯文本，支持 txt/pdf/doc/docx/md 等常见格式。
 */
@Service
public class DocumentExtractService {

    private final Tika tika = new Tika();

    public String extractText(MultipartFile file) {
        try {
            String text = tika.parseToString(file.getInputStream());
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("uploaded file has no readable text");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read uploaded file", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("unsupported or invalid document format", e);
        }
    }
}

package com.eureka.mcp.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/assets/screenshots")
public class ScreenshotController {

    @GetMapping("/{fileName}")
    public ResponseEntity<Resource> getScreenshot(@PathVariable String fileName) {
        if (fileName.contains("/") || fileName.contains("\\") || !fileName.toLowerCase().endsWith(".png")) {
            return ResponseEntity.badRequest().build();
        }

        Path path = Paths.get("data", "screenshots", fileName).normalize();
        if (!path.startsWith(Paths.get("data", "screenshots"))) {
            return ResponseEntity.badRequest().build();
        }

        Resource resource = new FileSystemResource(path);
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource);
    }
}

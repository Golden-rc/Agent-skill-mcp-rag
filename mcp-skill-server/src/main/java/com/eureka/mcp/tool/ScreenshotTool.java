package com.eureka.mcp.tool;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 截图工具：抓取当前主屏幕并保存为 PNG。
 */
@Component
public class ScreenshotTool implements McpTool {

    private final String publicBaseUrl;

    public ScreenshotTool(@Value("${app.public-base-url:http://localhost:8090}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public String name() {
        return "take_screenshot";
    }

    @Override
    public String description() {
        return "Capture current screen and save as PNG";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "fileName", Map.of(
                                "type", "string",
                                "description", "Optional PNG file name, e.g. demo.png"
                        )
                )
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String fileName = String.valueOf(arguments.getOrDefault("fileName", "")).trim();
        if (fileName.isEmpty()) {
            fileName = "screenshot-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
        }
        if (!fileName.toLowerCase().endsWith(".png")) {
            fileName = fileName + ".png";
        }

        Path outDir = Paths.get("data", "screenshots");
        Path outPath = outDir.resolve(fileName);

        try {
            Files.createDirectories(outDir);

            if (isMacOs()) {
                captureByMacCommand(outPath);
            } else {
                if (GraphicsEnvironment.isHeadless()) {
                    return "截图失败：当前环境是无头模式，无法捕获屏幕";
                }
                Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                Rectangle captureArea = new Rectangle(screenSize);
                BufferedImage image = new Robot().createScreenCapture(captureArea);
                ImageIO.write(image, "png", new File(outPath.toString()));
            }

            String base = publicBaseUrl.endsWith("/")
                    ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                    : publicBaseUrl;
            String imageUrl = base + "/assets/screenshots/" + fileName;
            return "截图成功: " + imageUrl;
        } catch (IOException | AWTException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "截图失败: " + e.getMessage();
        }
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private void captureByMacCommand(Path outPath) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("screencapture", "-x", outPath.toString()).start();
        int code = process.waitFor();
        if (code != 0 || !Files.exists(outPath)) {
            throw new IOException("screencapture command failed, exit=" + code);
        }
    }
}

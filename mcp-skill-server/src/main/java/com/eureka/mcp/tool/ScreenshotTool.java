package com.eureka.mcp.tool;

import org.springframework.stereotype.Component;

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
        if (GraphicsEnvironment.isHeadless()) {
            return "截图失败：当前环境是无头模式，无法捕获屏幕";
        }

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

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            Rectangle captureArea = new Rectangle(screenSize);
            BufferedImage image = new Robot().createScreenCapture(captureArea);
            ImageIO.write(image, "png", new File(outPath.toString()));
            return "截图成功: " + outPath.toAbsolutePath();
        } catch (IOException | AWTException e) {
            return "截图失败: " + e.getMessage();
        }
    }
}

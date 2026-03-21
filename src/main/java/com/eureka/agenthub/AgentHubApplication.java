package com.eureka.agenthub;

import com.eureka.agenthub.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
/**
 * Agent Hub 主启动类。
 * <p>
 * 负责启动 Spring Boot 容器并注册 {@link AppProperties} 配置映射。
 */
public class AgentHubApplication {

    /**
     * 应用入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentHubApplication.class, args);
    }
}

package com.financial.copilot.agent.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * DeepSeek 大模型连接配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekModelConfig {

    private String apiKey = "sk-placeholder";
    private String baseUrl = "https://api.deepseek.com/v1";
    private String modelName = "deepseek-chat"; // 或 deepseek-reasoner (R1)
    private Double temperature = 0.2; // 金融投研分析需要较低 temperature 保障确定性

    @Bean
    public WebClient deepSeekWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}

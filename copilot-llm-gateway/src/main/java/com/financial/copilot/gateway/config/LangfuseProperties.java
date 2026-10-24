package com.financial.copilot.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * <h1>Langfuse 大模型观测平台配置属性</h1>
 * <p>
 * 控制模型网关是否异步将大模型推理度量、Token 消耗、耗时与故障重试事实同步至 Langfuse 平台。
 * 支持本地私有化部署 (如 http://127.0.0.1:3000) 或官方云端 (https://cloud.langfuse.com)。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "copilot.gateway.langfuse")
public class LangfuseProperties {

    /**
     * 是否启用 Langfuse 观测上报（默认为 false）
     */
    private boolean enabled = false;

    /**
     * Langfuse API 服务端地址（默认官方云端，可配置为本地私有容器 http://127.0.0.1:3000）
     */
    private String host = "https://cloud.langfuse.com";

    /**
     * Langfuse Public Key (pk-lf-...)
     */
    private String publicKey = "";

    /**
     * Langfuse Secret Key (sk-lf-...)
     */
    private String secretKey = "";

    /**
     * 运行环境标签标识 (如 development, staging, production)
     */
    private String environment = "development";

    /**
     * 远程网络请求超时时限 (秒，默认 5 秒)
     */
    private int timeoutSeconds = 5;
}

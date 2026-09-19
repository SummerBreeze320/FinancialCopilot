package com.financial.copilot.agent.core.memory.remote;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 远程 Python 长期记忆服务客户端配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "copilot.memory.remote-service")
public class MemoryServiceProperties {

    /**
     * 是否启用远程 Python 记忆微服务（默认 true）
     */
    private boolean enabled = true;

    /**
     * 记忆微服务 HTTP 基础地址
     */
    private String baseUrl = "http://localhost:8000";

    /**
     * 在线检索超时时间（毫秒，默认 200ms）
     */
    private int timeoutMs = 200;

    /**
     * 默认单次 Prompt 记忆 Token 预算上限
     */
    private int defaultTokenBudget = 500;
}

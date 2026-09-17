package com.financial.copilot.agent.tools.configured.client;

import com.financial.copilot.agent.tools.configured.registry.ToolProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * 远程 Wind 组件数据按需读取客户端。
 * 调用 WIND_FUNDRESEARCH_SERVICE_URL/IR/ComponentReader.GetContent 获取组件明细数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComponentDataClient {

    private final ToolProperties properties;
    private final WebClient.Builder webClientBuilder;

    public String read(String contextId, String refId) {
        if (!StringUtils.hasText(properties.getComponentInvokeServiceUrl())) {
            log.warn("component-invoke-service-url is not configured, returning empty content for refId: {}", refId);
            return "";
        }
        try {
            Object response = webClientBuilder.build()
                    .post()
                    .uri(properties.getComponentInvokeServiceUrl() + "/IR/ComponentReader.GetContent")
                    .bodyValue(Map.of("contextId", contextId != null ? contextId : "", "refId", refId != null ? refId : ""))
                    .retrieve()
                    .bodyToMono(Object.class)
                    .block(Duration.ofMillis(properties.getHttpTimeoutMs()));
            return response == null ? "" : String.valueOf(response);
        } catch (Exception e) {
            log.error("Failed to read component data for refId: {}, contextId: {}", refId, contextId, e);
            throw new IllegalStateException("读取组件数据失败: " + e.getMessage(), e);
        }
    }
}

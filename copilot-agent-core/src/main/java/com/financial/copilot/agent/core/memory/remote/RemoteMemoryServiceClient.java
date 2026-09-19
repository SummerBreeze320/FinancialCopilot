package com.financial.copilot.agent.core.memory.remote;

import com.financial.copilot.agent.core.memory.remote.dto.MemoryBundleDTO;
import com.financial.copilot.agent.core.memory.remote.dto.ProcessSessionRequestDTO;
import com.financial.copilot.agent.core.memory.remote.dto.RecallRequestDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * <h1>远程 Python 长期记忆微服务 HTTP 客户端</h1>
 * <p>
 * 采用响应式 WebClient 封装底层通信，具备：
 * 1. 严格在线检索超时控制（默认 200ms），超时自动降级；
 * 2. 异常静默兜底，保障 Agent 主业务链路高可用；
 * 3. 异步离线会话记忆沉淀投递（fire-and-forget）。
 * </p>
 */
@Slf4j
@Component
public class RemoteMemoryServiceClient {

    private final MemoryServiceProperties properties;
    private final WebClient webClient;

    @Autowired
    public RemoteMemoryServiceClient(MemoryServiceProperties properties,
                                    @Autowired(required = false) WebClient.Builder webClientBuilder) {
        this.properties = properties;
        WebClient.Builder builder = webClientBuilder != null ? webClientBuilder : WebClient.builder();
        String baseUrl = (properties != null && properties.getBaseUrl() != null)
                ? properties.getBaseUrl()
                : "http://localhost:8000";
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    public RemoteMemoryServiceClient(MemoryServiceProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    /**
     * 在线记忆检索：以非阻塞 WebClient 请求 Python 记忆读取器端点 /api/v1/memory/recall
     *
     * @param request 检索请求（包含用户ID、查询文本、任务类型、Token预算）
     * @return 召回的记忆数据包（若失败、超时或服务未启用则返回 empty）
     */
    public Optional<MemoryBundleDTO> recall(RecallRequestDTO request) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        if (request == null || request.getUserId() == null || request.getQueryText() == null) {
            return Optional.empty();
        }

        try {
            int timeoutMs = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 200;
            if (request.getTokenBudget() == null || request.getTokenBudget() <= 0) {
                request.setTokenBudget(properties.getDefaultTokenBudget());
            }

            MemoryBundleDTO response = webClient.post()
                    .uri("/api/v1/memory/recall")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(MemoryBundleDTO.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .onErrorResume(e -> {
                        log.warn("[RemoteMemoryClient] Recall failed or timed out for user={}: {}",
                                request.getUserId(), e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("[RemoteMemoryClient] Unexpected error calling remote memory recall: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 异步触发离线会话记忆沉淀：以 fire-and-forget 方式投递至 /api/v1/memory/process-session
     *
     * @param request 会话离线加工参数
     */
    public void processSessionAsync(ProcessSessionRequestDTO request) {
        if (!isEnabled()) {
            return;
        }
        if (request == null || request.getSessionId() == null) {
            return;
        }

        try {
            webClient.post()
                    .uri("/api/v1/memory/process-session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .subscribe(
                            resp -> log.info("[RemoteMemoryClient] Session {} submitted to offline pipeline successfully: {}",
                                    request.getSessionId(), resp),
                            err -> log.warn("[RemoteMemoryClient] Failed submitting session {} to offline pipeline: {}",
                                    request.getSessionId(), err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("[RemoteMemoryClient] Error initiating processSessionAsync for session {}: {}",
                    request.getSessionId(), e.getMessage());
        }
    }
}

package com.financial.copilot.gateway.governance;

import com.financial.copilot.gateway.config.LangfuseProperties;
import com.financial.copilot.gateway.model.LlmAuditRecord;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <h1>Langfuse 审计事件上报器 (LangfuseAuditReporter)</h1>
 * <p>
 * 监听 {@link GatewayAuditSink} 广播的模型推理度量事件，异步构建符合 Langfuse Ingestion v2
 * 协议规范的批次事件载荷（包含 Trace 与 Generation），将模型调用、Token消耗、延迟与重试全量同步至 Langfuse 面板。
 * </p>
 * <p>
 * 遵循非侵入与平滑降级原则：当网络不可达或服务端异常时，静默捕获并记录 Warn 日志，绝不阻断金融研究业务主流程。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class LangfuseAuditReporter {

    private final LangfuseProperties properties;
    private final GatewayAuditSink auditSink;
    private final WebClient webClient;

    @Autowired
    public LangfuseAuditReporter(
            LangfuseProperties properties,
            GatewayAuditSink auditSink,
            @Autowired(required = false) WebClient.Builder webClientBuilder
    ) {
        this.properties = properties != null ? properties : new LangfuseProperties();
        this.auditSink = auditSink;

        WebClient.Builder builder = webClientBuilder != null ? webClientBuilder : WebClient.builder();
        this.webClient = builder
                .baseUrl(this.properties.getHost())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @PostConstruct
    public void init() {
        if (auditSink != null) {
            auditSink.addAuditListener(this::reportAuditRecord);
            if (properties.isEnabled()) {
                log.info("[LANGFUSE] 模型网关 Langfuse 审计上报器已就绪: host={}, env={}",
                        properties.getHost(), properties.getEnvironment());
            }
        }
    }

    /**
     * 接收并异步向 Langfuse 上报单条模型审计记录
     *
     * @param record 审计记录
     * @return 响应式 Mono 句柄
     */
    public Mono<Void> reportAuditRecord(LlmAuditRecord record) {
        if (!properties.isEnabled() || record == null) {
            return Mono.empty();
        }

        if (properties.getPublicKey() == null || properties.getPublicKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            log.debug("[LANGFUSE] Langfuse 密钥未配置，跳过上报: traceId={}", record.traceId());
            return Mono.empty();
        }

        try {
            Map<String, Object> payload = buildIngestionPayload(record);
            String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                    (properties.getPublicKey().trim() + ":" + properties.getSecretKey().trim()).getBytes(StandardCharsets.UTF_8)
            );

            return webClient.post()
                    .uri("/api/public/ingestion")
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .doOnSuccess(resp -> log.debug("[LANGFUSE] 审计记录上报成功: traceId={}, model={}", record.traceId(), record.actualModel()))
                    .doOnError(err -> log.warn("[LANGFUSE] 审计记录上报失败 (降级忽略): traceId={}, error={}", record.traceId(), err.getMessage()))
                    .onErrorResume(err -> Mono.empty())
                    .then();
        } catch (Exception ex) {
            log.warn("[LANGFUSE] 构造 Langfuse 上报载荷异常: {}", ex.getMessage());
            return Mono.empty();
        }
    }

    /**
     * 构造符合 Langfuse Ingestion API v2 规范的批处理载荷
     */
    public Map<String, Object> buildIngestionPayload(LlmAuditRecord record) {
        String isoNow = DateTimeFormatter.ISO_INSTANT.format(record.timestamp() != null ? record.timestamp() : Instant.now());
        String traceId = record.traceId() != null && !record.traceId().isBlank() ? record.traceId() : UUID.randomUUID().toString();
        String generationId = UUID.randomUUID().toString();

        List<Map<String, Object>> batch = new ArrayList<>();

        // 1. Trace 创建事件
        Map<String, Object> traceBody = new LinkedHashMap<>();
        traceBody.put("id", traceId);
        traceBody.put("name", "financial-research-run");
        traceBody.put("timestamp", isoNow);
        traceBody.put("environment", properties.getEnvironment());
        traceBody.put("metadata", Map.of(
                "scene", record.scene() != null ? record.scene().name() : "DEFAULT",
                "isFailover", record.isFailover()
        ));

        batch.add(Map.of(
                "id", UUID.randomUUID().toString(),
                "type", "trace-create",
                "timestamp", isoNow,
                "body", traceBody
        ));

        // 2. Generation 创建事件
        Map<String, Object> generationBody = new LinkedHashMap<>();
        generationBody.put("id", generationId);
        generationBody.put("traceId", traceId);
        generationBody.put("name", "llm.gateway." + (record.scene() != null ? record.scene().name() : "DEFAULT"));
        generationBody.put("startTime", isoNow);
        generationBody.put("endTime", isoNow);
        generationBody.put("model", record.actualModel());

        // Token 用量度量
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptTokens", record.promptTokens());
        usage.put("completionTokens", record.completionTokens());
        usage.put("totalTokens", record.totalTokens());
        generationBody.put("usage", usage);

        // 元数据
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", record.actualProvider());
        metadata.put("latencyMs", record.latencyMs());
        metadata.put("isFailover", record.isFailover());
        generationBody.put("metadata", metadata);

        // 状态与异常判定
        if (record.errorMessage() != null && !record.errorMessage().isBlank()) {
            generationBody.put("level", "ERROR");
            generationBody.put("statusMessage", record.errorMessage());
        } else {
            generationBody.put("level", "DEFAULT");
        }

        batch.add(Map.of(
                "id", UUID.randomUUID().toString(),
                "type", "generation-create",
                "timestamp", isoNow,
                "body", generationBody
        ));

        return Map.of("batch", batch);
    }
}

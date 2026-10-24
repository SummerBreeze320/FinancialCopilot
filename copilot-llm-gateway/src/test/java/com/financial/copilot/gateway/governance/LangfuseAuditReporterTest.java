package com.financial.copilot.gateway.governance;

import com.financial.copilot.gateway.config.LangfuseProperties;
import com.financial.copilot.gateway.model.LlmAuditRecord;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("LangfuseAuditReporter 观测上报器测试")
class LangfuseAuditReporterTest {

    @Test
    @DisplayName("未启用时不进行远程上报")
    void shouldSkipWhenDisabled() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(false);

        GatewayAuditSink sink = new GatewayAuditSink();
        LangfuseAuditReporter reporter = new LangfuseAuditReporter(properties, sink, WebClient.builder());

        LlmAuditRecord record = LlmAuditRecord.builder()
                .traceId("tr-test-1")
                .scene(LlmRoutingScene.FAST)
                .actualModel("gemini-3.8-flash-low")
                .actualProvider("codex-bridge")
                .promptTokens(100)
                .completionTokens(50)
                .totalTokens(150)
                .latencyMs(250L)
                .timestamp(Instant.now())
                .build();

        assertThatCode(() -> reporter.reportAuditRecord(record).block())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("构建符合 Langfuse Ingestion v2 规范的批处理载荷")
    @SuppressWarnings("unchecked")
    void shouldBuildValidIngestionPayload() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setPublicKey("pk-lf-test");
        properties.setSecretKey("sk-lf-test");
        properties.setEnvironment("staging");

        GatewayAuditSink sink = new GatewayAuditSink();
        LangfuseAuditReporter reporter = new LangfuseAuditReporter(properties, sink, WebClient.builder());

        LlmAuditRecord record = LlmAuditRecord.builder()
                .traceId("tr-abc-123")
                .scene(LlmRoutingScene.REASONING)
                .actualModel("deepseek-reasoner")
                .actualProvider("deepseek")
                .promptTokens(120)
                .completionTokens(380)
                .totalTokens(500)
                .latencyMs(1200L)
                .isFailover(true)
                .timestamp(Instant.now())
                .build();

        Map<String, Object> payload = reporter.buildIngestionPayload(record);

        assertThat(payload).containsKey("batch");
        List<Map<String, Object>> batch = (List<Map<String, Object>>) payload.get("batch");
        assertThat(batch).hasSize(2);

        // 验证 trace-create
        Map<String, Object> traceEvent = batch.get(0);
        assertThat(traceEvent.get("type")).isEqualTo("trace-create");
        Map<String, Object> traceBody = (Map<String, Object>) traceEvent.get("body");
        assertThat(traceBody.get("id")).isEqualTo("tr-abc-123");
        assertThat(traceBody.get("environment")).isEqualTo("staging");

        // 验证 generation-create
        Map<String, Object> genEvent = batch.get(1);
        assertThat(genEvent.get("type")).isEqualTo("generation-create");
        Map<String, Object> genBody = (Map<String, Object>) genEvent.get("body");
        assertThat(genBody.get("traceId")).isEqualTo("tr-abc-123");
        assertThat(genBody.get("model")).isEqualTo("deepseek-reasoner");
        assertThat(genBody.get("name")).isEqualTo("llm.gateway.REASONING");
        assertThat(genBody.get("level")).isEqualTo("DEFAULT");

        Map<String, Object> usage = (Map<String, Object>) genBody.get("usage");
        assertThat(usage.get("totalTokens")).isEqualTo(500);

        Map<String, Object> meta = (Map<String, Object>) genBody.get("metadata");
        assertThat(meta.get("isFailover")).isEqualTo(true);
        assertThat(meta.get("latencyMs")).isEqualTo(1200L);
    }

    @Test
    @DisplayName("存在错误信息时设置 generation 为 ERROR 级别")
    @SuppressWarnings("unchecked")
    void shouldSetErrorLevelWhenFailed() {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);

        GatewayAuditSink sink = new GatewayAuditSink();
        LangfuseAuditReporter reporter = new LangfuseAuditReporter(properties, sink, WebClient.builder());

        LlmAuditRecord record = LlmAuditRecord.builder()
                .traceId("tr-err-999")
                .scene(LlmRoutingScene.STANDARD)
                .actualModel("unknown")
                .actualProvider("unknown")
                .errorMessage("Connection refused to 127.0.0.1:9090")
                .timestamp(Instant.now())
                .build();

        Map<String, Object> payload = reporter.buildIngestionPayload(record);
        List<Map<String, Object>> batch = (List<Map<String, Object>>) payload.get("batch");
        Map<String, Object> genBody = (Map<String, Object>) batch.get(1).get("body");

        assertThat(genBody.get("level")).isEqualTo("ERROR");
        assertThat(genBody.get("statusMessage")).isEqualTo("Connection refused to 127.0.0.1:9090");
    }
}

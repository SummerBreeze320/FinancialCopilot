package com.financial.copilot.gateway.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>LlmGatewayModelSerializationTest</h1>
 * <p>网关核心数据传输模型序列化、反序列化与不可变性测试。</p>
 */
@DisplayName("LlmGateway 核心领域模型序列化与不可变性测试")
class LlmGatewayModelSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("LlmRoutingScene 枚举场景覆盖测试")
    void testRoutingScenes() {
        assertThat(LlmRoutingScene.values()).containsExactly(
                LlmRoutingScene.FAST,
                LlmRoutingScene.STANDARD,
                LlmRoutingScene.REASONING,
                LlmRoutingScene.QUANT,
                LlmRoutingScene.FALLBACK
        );
    }

    @Test
    @DisplayName("LlmGatewayRequest 构建与默认值测试")
    void testGatewayRequest() {
        LlmGatewayRequest request = LlmGatewayRequest.builder()
                .systemPrompt("system")
                .userPrompt("user")
                .scene(LlmRoutingScene.FAST)
                .preferredModel("gemini-3.8-flash-low")
                .temperature(0.7)
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(10))
                .metadata(Map.of("userId", 1001L))
                .build();

        assertThat(request.systemPrompt()).isEqualTo("system");
        assertThat(request.userPrompt()).isEqualTo("user");
        assertThat(request.scene()).isEqualTo(LlmRoutingScene.FAST);
        assertThat(request.preferredModel()).isEqualTo("gemini-3.8-flash-low");
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("LlmGatewayResponse 序列化测试")
    void testGatewayResponse() throws Exception {
        LlmGatewayResponse response = LlmGatewayResponse.builder()
                .content("hello world")
                .reasoningContent("thinking...")
                .actualModel("gemini-3.8-flash-low")
                .actualProvider("codex-bridge")
                .promptTokens(10)
                .completionTokens(20)
                .totalTokens(30)
                .latencyMs(150L)
                .isFailover(false)
                .build();

        String json = mapper.writeValueAsString(response);
        LlmGatewayResponse deserialized = mapper.readValue(json, LlmGatewayResponse.class);

        assertThat(deserialized.content()).isEqualTo("hello world");
        assertThat(deserialized.reasoningContent()).isEqualTo("thinking...");
        assertThat(deserialized.totalTokens()).isEqualTo(30);
        assertThat(deserialized.isFailover()).isFalse();
    }

    @Test
    @DisplayName("LlmAuditRecord 序列化测试")
    void testAuditRecord() throws Exception {
        Instant now = Instant.now();
        LlmAuditRecord record = LlmAuditRecord.builder()
                .traceId("trace-123")
                .scene(LlmRoutingScene.STANDARD)
                .actualModel("deepseek-chat")
                .actualProvider("online")
                .promptTokens(50)
                .completionTokens(100)
                .totalTokens(150)
                .latencyMs(320L)
                .isFailover(true)
                .errorMessage(null)
                .timestamp(now)
                .build();

        String json = mapper.writeValueAsString(record);
        LlmAuditRecord deserialized = mapper.readValue(json, LlmAuditRecord.class);

        assertThat(deserialized.traceId()).isEqualTo("trace-123");
        assertThat(deserialized.scene()).isEqualTo(LlmRoutingScene.STANDARD);
        assertThat(deserialized.isFailover()).isTrue();
    }

    @Test
    @DisplayName("LlmHealthReport 状态测试")
    void testHealthReport() {
        LlmHealthReport report = LlmHealthReport.builder()
                .healthy(true)
                .latencyMs(25L)
                .details(Map.of("codex-bridge", true))
                .reportTime(Instant.now())
                .build();

        assertThat(report.healthy()).isTrue();
        assertThat(report.details()).containsEntry("codex-bridge", true);
    }
}

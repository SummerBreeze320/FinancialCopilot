package com.financial.copilot.gateway.governance;

import com.financial.copilot.gateway.model.LlmAuditRecord;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>GatewayAuditSinkTest</h1>
 * <p>全链路审计记录广播与内存环形缓冲测试。</p>
 */
@DisplayName("GatewayAuditSink 全链路审计接收器测试")
class GatewayAuditSinkTest {

    @Test
    @DisplayName("记录审计事件并触发已注册的监听器")
    void shouldRecordAndNotifyListeners() {
        GatewayAuditSink sink = new GatewayAuditSink(10);
        AtomicReference<LlmAuditRecord> captured = new AtomicReference<>();
        sink.addAuditListener(captured::set);

        LlmAuditRecord record = LlmAuditRecord.builder()
                .traceId("tr-1")
                .scene(LlmRoutingScene.FAST)
                .actualModel("gemini-3.8-flash-low")
                .actualProvider("codex-bridge")
                .promptTokens(10)
                .completionTokens(20)
                .totalTokens(30)
                .latencyMs(120L)
                .isFailover(false)
                .timestamp(Instant.now())
                .build();

        sink.record(record);

        assertThat(captured.get()).isEqualTo(record);
        assertThat(sink.recentRecords(5)).containsExactly(record);
    }
}

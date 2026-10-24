package com.financial.copilot.agent.core.infra.event;

import com.financial.copilot.agent.core.infra.agent.AgentRole;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FrameworkEventBridgeTest {

    private FrameworkEventBridge bridge;
    private Sinks.Many<ResearchStreamEvent> sink;
    private List<ResearchStreamEvent> recordedEvents;

    @BeforeEach
    void setUp() {
        bridge = new FrameworkEventBridge();
        sink = Sinks.many().multicast().onBackpressureBuffer();
        recordedEvents = new ArrayList<>();
        sink.asFlux().subscribe(recordedEvents::add);
    }

    @Test
    void emitsNativeMultiAgentLifecycleEventsWithMonotonicSequenceNumbers() {
        String runId = "test-run-123";
        String conversationId = "conv-456";

        bridge.emitRunQueued(sink, runId, conversationId, "排队中", 1);
        bridge.emitRunStarted(sink, runId, conversationId);
        bridge.emitSupervisorThinking(sink, runId, "正在分解意图并委派公募基金专家");
        bridge.emitSubagentStarted(sink, runId, "agent-run-1", AgentRole.FUND_RESEARCH);
        bridge.emitToolCalled(sink, runId, "agent-run-1", AgentRole.FUND_RESEARCH, "getFundMetrics", Map.of("code", "005827"));
        bridge.emitToolCompleted(sink, runId, "agent-run-1", AgentRole.FUND_RESEARCH, "getFundMetrics", "查询指标完成");
        bridge.emitSubagentCompleted(sink, runId, "agent-run-1", AgentRole.FUND_RESEARCH, "基金分析完毕", 2);
        bridge.emitContentChunk(sink, runId, "## 投研分析报告\n易方达蓝筹精选...");
        bridge.emitRunCompleted(sink, runId, 1500L, 2);

        assertThat(recordedEvents).hasSize(9);

        // Verify strictly monotonic sequence numbers
        for (int i = 0; i < recordedEvents.size(); i++) {
            assertThat(recordedEvents.get(i).getSequenceNo()).isEqualTo((long) (i + 1));
        }

        // Verify native event types
        assertThat(recordedEvents.get(0).getType()).isEqualTo("run_queued");
        assertThat(recordedEvents.get(1).getType()).isEqualTo("run_started");
        assertThat(recordedEvents.get(2).getType()).isEqualTo("supervisor_thinking");
        assertThat(recordedEvents.get(2).getAgentRole()).isEqualTo("research_supervisor");

        assertThat(recordedEvents.get(3).getType()).isEqualTo("subagent_started");
        assertThat(recordedEvents.get(3).getAgentRole()).isEqualTo(AgentRole.FUND_RESEARCH.getAgentName());

        assertThat(recordedEvents.get(4).getType()).isEqualTo("tool_called");
        assertThat(recordedEvents.get(4).getToolName()).isEqualTo("getFundMetrics");

        assertThat(recordedEvents.get(5).getType()).isEqualTo("tool_completed");
        assertThat(recordedEvents.get(6).getType()).isEqualTo("subagent_completed");
        assertThat(recordedEvents.get(6).getEvidenceCount()).isEqualTo(2);

        assertThat(recordedEvents.get(7).getType()).isEqualTo("content_chunk");
        assertThat(recordedEvents.get(7).getChunk()).contains("易方达蓝筹精选");

        assertThat(recordedEvents.get(8).getType()).isEqualTo("run_completed");
        assertThat(recordedEvents.get(8).getDurationMs()).isEqualTo(1500L);
        assertThat(recordedEvents.get(8).getEvidenceCount()).isEqualTo(2);
    }

    @Test
    void emitsRunFailedAndCancelledProperly() {
        String runId = "err-run";

        bridge.emitRunStarted(sink, runId, "conv-err");
        bridge.emitRunFailed(sink, runId, "风控输入拦截");

        assertThat(recordedEvents).hasSize(2);
        assertThat(recordedEvents.get(1).getType()).isEqualTo("run_failed");
        assertThat(recordedEvents.get(1).getSummary()).isEqualTo("风控输入拦截");

        String cancelRunId = "cancel-run";
        bridge.emitRunStarted(sink, cancelRunId, "conv-cancel");
        bridge.emitRunCancelled(sink, cancelRunId, "用户手动取消");

        assertThat(recordedEvents).hasSize(4);
        assertThat(recordedEvents.get(3).getType()).isEqualTo("run_cancelled");
        assertThat(recordedEvents.get(3).getSummary()).isEqualTo("用户手动取消");
    }
}

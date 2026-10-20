package com.financial.copilot.agent.core.infra.agentscope.loop;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>AgentLoopSupervisor 单元测试</h1>
 * <p>覆盖重复工具调用拦截、振荡环路检测、失败熔断硬拦截及安全降级。</p>
 */
@DisplayName("AgentLoopSupervisor 防死循环与终止条件测试")
class AgentLoopSupervisorTest {

    private AgentLoopSupervisor supervisor;
    private Map<String, List<ToolResultBlock>> observations;

    @BeforeEach
    void setUp() {
        supervisor = new AgentLoopSupervisor(5, 2, 2);
        observations = new ConcurrentHashMap<>();
    }

    @Test
    @DisplayName("连续调用完全相同工具与参数时，触发连续重复调用拦截并安全终止")
    void shouldInterceptConsecutiveDuplicateToolCalls() {
        ToolUseBlock call1 = ToolUseBlock.builder()
                .id("call-1")
                .name("fund_metrics")
                .input(Map.of("fundCode", "000001", "year", 2024))
                .build();
        ChatResponse resp1 = ChatResponse.builder()
                .id("resp-1")
                .finishReason("tool_calls")
                .content(List.of(call1))
                .build();

        ChatResponse result1 = supervisor.inspectAndGuard(resp1, observations);
        assertThat(result1.getFinishReason()).isEqualTo("tool_calls");
        assertThat(supervisor.isTerminated()).isFalse();

        // 模拟同一工具传入参数顺序不同但内容完全一致（规范化指纹去重）
        ToolUseBlock call2 = ToolUseBlock.builder()
                .id("call-2")
                .name("fund_metrics")
                .input(Map.of("year", 2024, "fundCode", "000001"))
                .build();
        ChatResponse resp2 = ChatResponse.builder()
                .id("resp-2")
                .finishReason("tool_calls")
                .content(List.of(call2))
                .build();

        ChatResponse result2 = supervisor.inspectAndGuard(resp2, observations);

        // 验证被拦截并转为终止态 stop
        assertThat(result2.getFinishReason()).isEqualTo("stop");
        assertThat(supervisor.isTerminated()).isTrue();
        assertThat(supervisor.getTerminationReason()).isEqualTo("CONSECUTIVE_DUPLICATE_CALL");
        assertThat(result2.getContent().get(0)).isInstanceOf(TextBlock.class);
        TextBlock textBlock = (TextBlock) result2.getContent().get(0);
        assertThat(textBlock.getText()).contains("检测到以完全相同的入参连续重复调用工具 [fund_metrics]");
    }

    @Test
    @DisplayName("连续调用同一工具但入参不同时，正常放行")
    void shouldAllowCallsWithDifferentParameters() {
        ToolUseBlock call1 = ToolUseBlock.builder()
                .id("call-1")
                .name("fund_metrics")
                .input(Map.of("fundCode", "000001"))
                .build();
        ChatResponse resp1 = ChatResponse.builder().id("r1").finishReason("tool_calls").content(List.of(call1)).build();
        assertThat(supervisor.inspectAndGuard(resp1, observations).getFinishReason()).isEqualTo("tool_calls");

        ToolUseBlock call2 = ToolUseBlock.builder()
                .id("call-2")
                .name("fund_metrics")
                .input(Map.of("fundCode", "000002"))
                .build();
        ChatResponse resp2 = ChatResponse.builder().id("r2").finishReason("tool_calls").content(List.of(call2)).build();
        assertThat(supervisor.inspectAndGuard(resp2, observations).getFinishReason()).isEqualTo("tool_calls");
        assertThat(supervisor.isTerminated()).isFalse();
    }

    @Test
    @DisplayName("检测到 A -> B -> A -> B 振荡循环时，判定为死锁并触发终止")
    void shouldDetectPingPongOscillationLoop() {
        ToolUseBlock callA1 = ToolUseBlock.builder().id("a1").name("toolA").input(Map.of("k", "v1")).build();
        ToolUseBlock callB1 = ToolUseBlock.builder().id("b1").name("toolB").input(Map.of("k", "v2")).build();
        ToolUseBlock callA2 = ToolUseBlock.builder().id("a2").name("toolA").input(Map.of("k", "v1")).build();
        ToolUseBlock callB2 = ToolUseBlock.builder().id("b2").name("toolB").input(Map.of("k", "v2")).build();

        supervisor.inspectAndGuard(ChatResponse.builder().id("r1").finishReason("tool_calls").content(List.of(callA1)).build(), observations);
        supervisor.inspectAndGuard(ChatResponse.builder().id("r2").finishReason("tool_calls").content(List.of(callB1)).build(), observations);
        supervisor.inspectAndGuard(ChatResponse.builder().id("r3").finishReason("tool_calls").content(List.of(callA2)).build(), observations);

        ChatResponse resp4 = supervisor.inspectAndGuard(
                ChatResponse.builder().id("r4").finishReason("tool_calls").content(List.of(callB2)).build(),
                observations
        );

        assertThat(resp4.getFinishReason()).isEqualTo("stop");
        assertThat(supervisor.isTerminated()).isTrue();
        assertThat(supervisor.getTerminationReason()).isEqualTo("PING_PONG_OSCILLATION");
        TextBlock textBlock = (TextBlock) resp4.getContent().get(0);
        assertThat(textBlock.getText()).contains("振荡");
    }

    @Test
    @DisplayName("工具连续失败达到阈值进入熔断，再次调用时触发硬阻断")
    void shouldTripCircuitBreakerAfterConsecutiveFailures() {
        ToolResultBlock fail1 = ToolResultBlock.builder()
                .id("t1")
                .name("failing_tool")
                .state(ToolResultState.ERROR)
                .output(List.of(TextBlock.builder().text("timeout").build()))
                .build();
        ToolResultBlock fail2 = ToolResultBlock.builder()
                .id("t2")
                .name("failing_tool")
                .state(ToolResultState.ERROR)
                .output(List.of(TextBlock.builder().text("timeout again").build()))
                .build();

        supervisor.recordObservations(List.of(fail1));
        assertThat(supervisor.isToolTripped("failing_tool")).isFalse();

        supervisor.recordObservations(List.of(fail2));
        assertThat(supervisor.isToolTripped("failing_tool")).isTrue();

        // 再次尝试调用已熔断工具
        ToolUseBlock call3 = ToolUseBlock.builder()
                .id("t3")
                .name("failing_tool")
                .input(Map.of("k", "v3"))
                .build();
        ChatResponse resp = supervisor.inspectAndGuard(
                ChatResponse.builder().id("r3").finishReason("tool_calls").content(List.of(call3)).build(),
                observations
        );

        assertThat(resp.getFinishReason()).isEqualTo("stop");
        assertThat(supervisor.isTerminated()).isTrue();
        assertThat(supervisor.getTerminationReason()).isEqualTo("TOOL_CIRCUIT_BROKEN");
    }

    @Test
    @DisplayName("普通文本输出直接放行，不影响正常终止判定")
    void shouldPassThroughNormalTextResponse() {
        ChatResponse normalTextResp = ChatResponse.builder()
                .id("r-normal")
                .finishReason("stop")
                .content(List.of(TextBlock.builder().text("任务已圆满完成").build()))
                .build();

        ChatResponse result = supervisor.inspectAndGuard(normalTextResp, observations);
        assertThat(result.getFinishReason()).isEqualTo("stop");
        assertThat(supervisor.isTerminated()).isFalse();
        assertThat(supervisor.getTerminationStatus()).isEqualTo(AgentTerminationStatus.TASK_COMPLETED);
    }

    @Test
    @DisplayName("d2) 捕获权限拒绝不可恢复错误时，立即硬阻断并标记 PERMISSION_DENIED")
    void shouldInterceptPermissionDeniedUnrecoverableError() {
        ToolResultBlock forbiddenObs = ToolResultBlock.builder()
                .id("perm-1")
                .name("admin_rebalance")
                .state(ToolResultState.ERROR)
                .output(List.of(TextBlock.builder().text("403 Forbidden: 用户权限不足，无权调用此高风险工具").build()))
                .build();

        supervisor.recordObservations(List.of(forbiddenObs));
        assertThat(supervisor.isPermissionDenied("admin_rebalance")).isTrue();

        // 模型试图重试调用被拒绝的工具
        ToolUseBlock call = ToolUseBlock.builder()
                .id("perm-call")
                .name("admin_rebalance")
                .input(Map.of("target", "stocks"))
                .build();
        ChatResponse resp = supervisor.inspectAndGuard(
                ChatResponse.builder().id("r-perm").finishReason("tool_calls").content(List.of(call)).build(),
                observations
        );

        assertThat(resp.getFinishReason()).isEqualTo("stop");
        assertThat(supervisor.isTerminated()).isTrue();
        assertThat(supervisor.getTerminationStatus()).isEqualTo(AgentTerminationStatus.PERMISSION_DENIED);
        TextBlock text = (TextBlock) resp.getContent().get(0);
        assertThat(text.getText()).contains("权限校验拒绝");
    }

    @Test
    @DisplayName("c) 达到最大轮数硬上限时，主动收敛并标记 MAX_ITERATIONS_REACHED")
    void shouldTerminateWhenMaxIterationsReached() {
        AgentLoopSupervisor boundedSupervisor = new AgentLoopSupervisor(2, 2, 2);

        ToolUseBlock call1 = ToolUseBlock.builder().id("c1").name("t1").input(Map.of("v", 1)).build();
        boundedSupervisor.inspectAndGuard(
                ChatResponse.builder().id("r1").finishReason("tool_calls").content(List.of(call1)).build(),
                observations
        );

        ToolUseBlock call2 = ToolUseBlock.builder().id("c2").name("t2").input(Map.of("v", 2)).build();
        ChatResponse resp2 = boundedSupervisor.inspectAndGuard(
                ChatResponse.builder().id("r2").finishReason("tool_calls").content(List.of(call2)).build(),
                observations
        );

        assertThat(resp2.getFinishReason()).isEqualTo("stop");
        assertThat(boundedSupervisor.isTerminated()).isTrue();
        assertThat(boundedSupervisor.getTerminationStatus()).isEqualTo(AgentTerminationStatus.MAX_ITERATIONS_REACHED);
        assertThat(boundedSupervisor.getTerminationStatus().isDegraded()).isTrue();
    }

    @Test
    @DisplayName("外部取消中断时，正确记录 CANCELLED 状态")
    void shouldHandleExternalCancellation() {
        supervisor.recordCancellation();
        assertThat(supervisor.isTerminated()).isTrue();
        assertThat(supervisor.getTerminationStatus()).isEqualTo(AgentTerminationStatus.CANCELLED);
    }
}

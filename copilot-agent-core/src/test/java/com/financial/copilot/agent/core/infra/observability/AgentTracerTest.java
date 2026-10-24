package com.financial.copilot.agent.core.infra.observability;

import com.financial.copilot.agent.core.infra.agent.AgentRole;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTracer 分布式追踪与降级测试")
class AgentTracerTest {

    @Mock
    private Tracer mockTracer;

    @Mock
    private Span mockSpan;

    @Mock
    private TraceContext mockTraceContext;

    @Mock
    private Tracer.SpanInScope mockSpanInScope;

    @Test
    @DisplayName("当 Tracer 为空时，所有方法平滑降级为 No-Op，不抛出异常")
    void shouldGracefullyFallbackWhenTracerIsNull() {
        AgentTracer tracer = new AgentTracer(null);

        assertThat(tracer.startWorkflowSpan("wf-1", "user-1", "test")).isNull();
        assertThat(tracer.startSupervisorSpan("wf-1", "user-1")).isNull();
        assertThat(tracer.startSubagentSpan(AgentRole.FUND_RESEARCH, "wf-1", "ag-1", "tool")).isNull();
        assertThat(tracer.startToolSpan("fund_metrics", "wf-1")).isNull();
        assertThat(tracer.startGuardrailSpan("INPUT", "wf-1")).isNull();
        assertThat(tracer.startLlmSpan("deepseek-chat")).isNull();
        assertThat(tracer.getCurrentTraceId()).isNull();
        assertThat(tracer.getCurrentSpanId()).isNull();

        // withSpan 与 endSpan 对 null 安全
        AutoCloseable closeable = tracer.withSpan(null);
        assertThat(closeable).isNotNull();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(closeable::close);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> tracer.endSpan(null, null));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> tracer.endSpan(null, new RuntimeException("err")));

        String executed = tracer.inSpan("test", Map.of(), () -> "success");
        assertThat(executed).isEqualTo("success");
    }

    @Test
    @DisplayName("当 Tracer 可用时，正常开启 Span 并关联属性标签")
    void shouldCreateSpansWhenTracerIsAvailable() {
        when(mockTracer.nextSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockTracer.currentSpan()).thenReturn(mockSpan);
        when(mockSpan.context()).thenReturn(mockTraceContext);
        when(mockTraceContext.traceId()).thenReturn("trace-abc-123");
        when(mockTraceContext.spanId()).thenReturn("span-xyz-789");

        AgentTracer tracer = new AgentTracer(mockTracer);

        Span span = tracer.startSubagentSpan(AgentRole.FUND_RESEARCH, "wf-100", "ag-200", "screen_funds");
        assertThat(span).isNotNull();
        verify(mockSpan).name("agent.subagent.fund_research");
        verify(mockSpan).tag("gen_ai.agent.role", "FUND_RESEARCH");
        verify(mockSpan).tag("gen_ai.workflow.run_id", "wf-100");
        verify(mockSpan).tag("tool.name", "screen_funds");

        assertThat(tracer.getCurrentTraceId()).isEqualTo("trace-abc-123");
        assertThat(tracer.getCurrentSpanId()).isEqualTo("span-xyz-789");

        // 结束 Span 校验
        tracer.endSpan(span, null);
        verify(mockSpan).end();

        // 异常结束 Span 校验
        RuntimeException ex = new RuntimeException("test error");
        tracer.endSpan(span, ex);
        verify(mockSpan).error(ex);
        verify(mockSpan).tag("error", "true");
    }

    @Test
    @DisplayName("inSpan 闭包在发生异常时正确记录错误并关闭 Span")
    void shouldHandleExceptionInSpan() {
        when(mockTracer.nextSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockTracer.withSpan(mockSpan)).thenReturn(mockSpanInScope);

        AgentTracer tracer = new AgentTracer(mockTracer);

        assertThatThrownBy(() -> tracer.inSpan("error-op", null, () -> {
            throw new IllegalStateException("fatal error");
        })).isInstanceOf(IllegalStateException.class).hasMessage("fatal error");

        verify(mockSpan).error(any(IllegalStateException.class));
        verify(mockSpan).end();
        verify(mockSpanInScope).close();
    }
}

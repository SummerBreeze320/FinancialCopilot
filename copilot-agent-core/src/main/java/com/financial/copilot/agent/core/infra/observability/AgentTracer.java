package com.financial.copilot.agent.core.infra.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * <h1>多智能体全链路分布式追踪器 (AgentTracer)</h1>
 * <p>
 * 基于 OpenTelemetry 与 Micrometer Tracing 体系，为 Supervisor-Subagent 金融投研多智能体系统
 * 提供标准化分布式追踪（Distributed Tracing）能力。
 * </p>
 * <p>
 * 遵循 GenAI 观测规范 (OpenTelemetry GenAI Semantic Conventions) 与平台契约：
 * <ul>
 *   <li>覆盖从 Workflow 启动、Supervisor 编排、Subagent 委派、Tool 执行到 LLM 调用的完整 Span 链路；</li>
 *   <li>严密保护上下文传播，支持在响应式 Reactive 边界与虚拟线程（Virtual Thread）中无缝继承 TraceContext；</li>
 *   <li>无感知优雅降级：在测试环境或未配置 OTel 时完全安全无侵入（No-Op），零空指针风险。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class AgentTracer {

    private static final AutoCloseable NOOP_CLOSEABLE = () -> {};

    private final Tracer tracer;

    public AgentTracer(@Autowired(required = false) Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * 开启整个投研工作流根 Span (financial.research.workflow)
     */
    public Span startWorkflowSpan(String workflowRunId, String userId, String prompt) {
        if (tracer == null) {
            return null;
        }
        Span span = tracer.nextSpan()
                .name("financial.research.workflow")
                .tag("gen_ai.system", "financial-copilot")
                .tag("gen_ai.workflow.run_id", workflowRunId != null ? workflowRunId : "")
                .tag("user.id", userId != null ? userId : "");
        if (prompt != null && !prompt.isBlank()) {
            span.tag("gen_ai.prompt.preview", prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
        }
        return span.start();
    }

    /**
     * 开启 Supervisor 总控智能体 Span (agent.supervisor)
     */
    public Span startSupervisorSpan(String workflowRunId, String userId) {
        if (tracer == null) {
            return null;
        }
        return tracer.nextSpan()
                .name("agent.supervisor")
                .tag("gen_ai.system", "financial-copilot")
                .tag("gen_ai.agent.role", "SUPERVISOR")
                .tag("gen_ai.workflow.run_id", workflowRunId != null ? workflowRunId : "")
                .tag("user.id", userId != null ? userId : "")
                .start();
    }

    /**
     * 开启专业 Subagent 执行 Span (agent.subagent.<role>)
     */
    public Span startSubagentSpan(String roleName, String workflowRunId, String agentRunId, String toolName) {
        if (tracer == null) {
            return null;
        }
        String safeRole = roleName != null ? roleName : "UNKNOWN";
        return tracer.nextSpan()
                .name("agent.subagent." + safeRole.toLowerCase())
                .tag("gen_ai.system", "financial-copilot")
                .tag("gen_ai.agent.role", safeRole)
                .tag("gen_ai.agent.run_id", agentRunId != null ? agentRunId : "")
                .tag("gen_ai.workflow.run_id", workflowRunId != null ? workflowRunId : "")
                .tag("tool.name", toolName != null ? toolName : "")
                .start();
    }

    /**
     * 开启专业 Subagent 执行 Span (简化重载)
     */
    public Span startSubagentSpan(String roleName, String agentRunId) {
        return startSubagentSpan(roleName, null, agentRunId, null);
    }

    /**
     * 开启专业 Subagent 执行 Span (支持任意角色枚举类型)
     */
    public Span startSubagentSpan(Enum<?> role, String workflowRunId, String agentRunId, String toolName) {
        return startSubagentSpan(role != null ? role.name() : "UNKNOWN", workflowRunId, agentRunId, toolName);
    }

    public Span startSubagentSpan(Enum<?> role, String agentRunId) {
        return startSubagentSpan(role != null ? role.name() : "UNKNOWN", null, agentRunId, null);
    }

    /**
     * 开启底层专业工具 Span (agent.tool.<toolName>)
     */
    public Span startToolSpan(String toolName, String workflowRunId) {
        if (tracer == null) {
            return null;
        }
        return tracer.nextSpan()
                .name("agent.tool." + (toolName != null ? toolName : "unknown"))
                .tag("gen_ai.system", "financial-copilot")
                .tag("tool.name", toolName != null ? toolName : "")
                .tag("gen_ai.workflow.run_id", workflowRunId != null ? workflowRunId : "")
                .start();
    }

    /**
     * 开启安全护栏 Span (agent.guardrail.<type>)
     */
    public Span startGuardrailSpan(String guardrailType, String workflowRunId) {
        if (tracer == null) {
            return null;
        }
        return tracer.nextSpan()
                .name("agent.guardrail." + guardrailType.toLowerCase())
                .tag("gen_ai.system", "financial-copilot")
                .tag("guardrail.type", guardrailType)
                .tag("gen_ai.workflow.run_id", workflowRunId != null ? workflowRunId : "")
                .start();
    }

    /**
     * 开启大模型推理调用 Span (gen_ai.chat)
     */
    public Span startLlmSpan(String modelName) {
        if (tracer == null) {
            return null;
        }
        Span span = tracer.nextSpan()
                .name("gen_ai.chat")
                .tag("gen_ai.system", "financial-copilot");
        if (modelName != null && !modelName.isBlank()) {
            span.tag("gen_ai.request.model", modelName);
        }
        return span.start();
    }

    @FunctionalInterface
    public interface TraceScope extends AutoCloseable {
        @Override
        void close();
    }

    private static final TraceScope NOOP_SCOPE = () -> {};

    public static TraceScope noopScope() {
        return NOOP_SCOPE;
    }

    /**
     * 将当前 Span 激活绑定至当前线程（支持 try-with-resources 安全释放且无受检异常）
     */
    public TraceScope withSpan(Span span) {
        if (tracer == null || span == null) {
            return NOOP_SCOPE;
        }
        Tracer.SpanInScope ws = tracer.withSpan(span);
        return ws::close;
    }

    /**
     * 结束指定 Span，若包含未捕获异常则自动记录异常堆栈与错误标签
     */
    public void endSpan(Span span, Throwable throwable) {
        if (span == null) {
            return;
        }
        if (throwable != null) {
            span.error(throwable);
            span.tag("error", "true");
            span.tag("error.message", throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
        }
        span.end();
    }

    /**
     * 获取当前处于活跃状态的 Trace ID（Hex 字符串）
     */
    public String getCurrentTraceId() {
        if (tracer != null) {
            Span current = tracer.currentSpan();
            if (current != null && current.context() != null) {
                return current.context().traceId();
            }
        }
        return null;
    }

    /**
     * 获取当前处于活跃状态的 Span ID（Hex 字符串）
     */
    public String getCurrentSpanId() {
        if (tracer != null) {
            Span current = tracer.currentSpan();
            if (current != null && current.context() != null) {
                return current.context().spanId();
            }
        }
        return null;
    }

    /**
     * 同步闭包在指定子 Span 中执行，自动捕获异常并关闭作用域
     */
    public <T> T inSpan(String spanName, Map<String, String> tags, Supplier<T> supplier) {
        if (tracer == null) {
            return supplier.get();
        }
        Span span = tracer.nextSpan().name(spanName);
        if (tags != null) {
            tags.forEach(span::tag);
        }
        span.start();
        Throwable error = null;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return supplier.get();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            endSpan(span, error);
        }
    }
}

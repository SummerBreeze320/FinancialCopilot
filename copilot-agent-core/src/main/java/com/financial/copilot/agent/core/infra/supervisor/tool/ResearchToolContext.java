package com.financial.copilot.agent.core.infra.supervisor.tool;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;
import com.financial.copilot.agent.core.infra.supervisor.evidence.AgentEvidence;
import com.financial.copilot.agent.core.infra.supervisor.evidence.RunEvidenceStore;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchExecutionContext;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>Supervisor 工具隐式上下文支撑类 (ResearchToolContext)</h1>
 * <p>
 * 遵循平台契约，严禁通过大模型参数暴露或填写 {@code userId}、{@code conversationId}、{@code workflowRunId}、{@code agentRunId}。
 * 本工具类负责将运行级隔离凭证存入 Spring AI 的隐藏 {@link ToolContext}，
 * 并在确定性工具调用前后安全存取运行上下文和沉淀 {@link AgentEvidence} 事实证据。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class ResearchToolContext {

    public static final String KEY_EXECUTION_CONTEXT = "RESEARCH_EXECUTION_CONTEXT";
    public static final String KEY_EVIDENCE_STORE = "RUN_EVIDENCE_STORE";
    public static final String KEY_AGENT_ROLE = "CURRENT_AGENT_ROLE";
    public static final String KEY_AGENT_RUN_ID = "CURRENT_AGENT_RUN_ID";

    private ResearchToolContext() {}

    /**
     * 构建包含运行上下文、证据仓库与执行角色的 ToolContext 上下文字典
     */
    public static Map<String, Object> toContextMap(
            ResearchExecutionContext executionContext,
            RunEvidenceStore evidenceStore,
            AgentRole role,
            String agentRunId
    ) {
        Map<String, Object> map = new HashMap<>();
        map.put(KEY_EXECUTION_CONTEXT, Objects.requireNonNull(executionContext, "executionContext cannot be null"));
        map.put(KEY_EVIDENCE_STORE, Objects.requireNonNull(evidenceStore, "evidenceStore cannot be null"));
        map.put(KEY_AGENT_ROLE, Objects.requireNonNull(role, "role cannot be null"));
        map.put(KEY_AGENT_RUN_ID, (agentRunId == null || agentRunId.isBlank()) ? UUID.randomUUID().toString() : agentRunId);
        return map;
    }

    /**
     * 便捷构建 Spring AI ToolContext 实例
     */
    public static ToolContext createToolContext(
            ResearchExecutionContext executionContext,
            RunEvidenceStore evidenceStore,
            AgentRole role,
            String agentRunId
    ) {
        return new ToolContext(toContextMap(executionContext, evidenceStore, role, agentRunId));
    }

    /**
     * 从隐藏 ToolContext 中提取不可变运行上下文
     */
    public static ResearchExecutionContext extractExecutionContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("ToolContext is null or empty. Hidden execution context is required.");
        }
        Object obj = toolContext.getContext().get(KEY_EXECUTION_CONTEXT);
        if (!(obj instanceof ResearchExecutionContext ctx)) {
            throw new IllegalStateException("Missing or invalid " + KEY_EXECUTION_CONTEXT + " in ToolContext");
        }
        return ctx;
    }

    /**
     * 从隐藏 ToolContext 中提取证据仓库实例
     */
    public static RunEvidenceStore extractEvidenceStore(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("ToolContext is null or empty. RunEvidenceStore is required.");
        }
        Object obj = toolContext.getContext().get(KEY_EVIDENCE_STORE);
        if (!(obj instanceof RunEvidenceStore store)) {
            throw new IllegalStateException("Missing or invalid " + KEY_EVIDENCE_STORE + " in ToolContext");
        }
        return store;
    }

    /**
     * 提取当前执行 Subagent 角色
     */
    public static AgentRole extractRole(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return AgentRole.SUPERVISOR;
        }
        Object obj = toolContext.getContext().get(KEY_AGENT_ROLE);
        if (obj instanceof AgentRole role) {
            return role;
        }
        return AgentRole.SUPERVISOR;
    }

    /**
     * 提取当前 Agent 调用唯一 ID
     */
    public static String extractAgentRunId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return "run-unknown";
        }
        Object obj = toolContext.getContext().get(KEY_AGENT_RUN_ID);
        return obj != null ? obj.toString() : "run-unknown";
    }

    /**
     * 记录并追加一条确定性工具执行证据
     */
    public static AgentEvidence recordEvidence(
            ToolContext toolContext,
            String toolName,
            String summary,
            Object payload
    ) {
        ResearchExecutionContext execCtx = extractExecutionContext(toolContext);
        RunEvidenceStore store = extractEvidenceStore(toolContext);
        AgentRole role = extractRole(toolContext);
        String agentRunId = extractAgentRunId(toolContext);

        AgentEvidence evidence = AgentEvidence.of(
                role,
                agentRunId,
                toolName,
                summary,
                payload
        );
        store.append(execCtx.workflowRunId(), evidence);
        return evidence;
    }
}

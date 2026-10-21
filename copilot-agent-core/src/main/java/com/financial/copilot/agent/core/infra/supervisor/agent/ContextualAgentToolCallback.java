package com.financial.copilot.agent.core.infra.supervisor.agent;

import com.financial.copilot.agent.core.infra.supervisor.coordination.AgentOperationLedger;
import com.financial.copilot.agent.core.infra.supervisor.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.supervisor.evidence.RunEvidenceStore;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchExecutionContext;
import com.financial.copilot.agent.core.infra.supervisor.tool.ResearchToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>上下文隔离与幂等智能体工具回调包装器 (ContextualAgentToolCallback)</h1>
 * <p>
 * 遵循平台多智能体契约，对 Spring AI Alibaba 官方 {@link com.alibaba.cloud.ai.graph.agent.AgentTool} 产出的 {@link ToolCallback} 进行安全封装：
 * <ul>
 *   <li>完全继承底层 Subagent 的模型可见 Schema（不注入 tenantId/userId/runId 等破坏模型注意力的冗余参数）；</li>
 *   <li>从隐式 {@link ToolContext} 中读取 {@link ResearchExecutionContext}；</li>
 *   <li>为单次 Subagent 执行分配唯一 {@code agentRunId}；</li>
 *   <li>生成强幂等键并通过 {@link AgentOperationLedger} 进行并发抑重与执行追踪。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
public class ContextualAgentToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AgentRole role;
    private final AgentOperationLedger operationLedger;
    private final EvidenceMergeCoordinator evidenceCoordinator;

    public ContextualAgentToolCallback(
            ToolCallback delegate,
            AgentRole role,
            AgentOperationLedger operationLedger,
            EvidenceMergeCoordinator evidenceCoordinator
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate ToolCallback cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.operationLedger = Objects.requireNonNull(operationLedger, "operationLedger cannot be null");
        this.evidenceCoordinator = Objects.requireNonNull(evidenceCoordinator, "evidenceCoordinator cannot be null");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        ResearchExecutionContext execCtx = null;
        RunEvidenceStore store = null;

        if (toolContext != null && toolContext.getContext() != null) {
            Object ctxObj = toolContext.getContext().get(ResearchToolContext.KEY_EXECUTION_CONTEXT);
            if (ctxObj instanceof ResearchExecutionContext rec) {
                execCtx = rec;
            }
            Object storeObj = toolContext.getContext().get(ResearchToolContext.KEY_EVIDENCE_STORE);
            if (storeObj instanceof RunEvidenceStore res) {
                store = res;
            }
        }

        if (execCtx == null || store == null) {
            ToolContext current = ResearchToolContext.currentContext();
            if (current != null && current.getContext() != null) {
                if (execCtx == null && current.getContext().get(ResearchToolContext.KEY_EXECUTION_CONTEXT) instanceof ResearchExecutionContext rec) {
                    execCtx = rec;
                }
                if (store == null && current.getContext().get(ResearchToolContext.KEY_EVIDENCE_STORE) instanceof RunEvidenceStore res) {
                    store = res;
                }
            }
        }

        String workflowRunId = execCtx != null ? execCtx.workflowRunId() : "standalone-run";
        String agentRunId = "agent-" + UUID.randomUUID();
        String toolName = delegate.getToolDefinition().name();
        String inputHash = sha256Hex(toolInput != null ? toolInput : "");
        String idempotencyKey = workflowRunId + ":" + role.name() + ":" + toolName + ":" + inputHash;

        log.info("[SUBAGENT-INVOKE] role={}, tool={}, workflowRunId={}, agentRunId={}",
                role, toolName, workflowRunId, agentRunId);

        final ToolContext childContext = (execCtx != null && store != null)
                ? ResearchToolContext.createToolContext(execCtx, store, role, agentRunId)
                : toolContext;

        try (AutoCloseable ignored = ResearchToolContext.withContext(childContext)) {
            return operationLedger.executeOnce(
                    idempotencyKey,
                    workflowRunId,
                    agentRunId,
                    role,
                    toolName,
                    () -> delegate.call(toolInput, childContext)
            );
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Subagent invocation failed: " + e.getMessage(), e);
        }
    }

    public AgentRole getRole() {
        return role;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}

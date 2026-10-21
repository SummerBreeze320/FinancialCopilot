package com.financial.copilot.agent.core.infra.supervisor.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.financial.copilot.agent.core.infra.supervisor.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.supervisor.evidence.AgentEvidence;
import com.financial.copilot.agent.core.infra.supervisor.evidence.RunEvidenceStore;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchExecutionContext;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunResult;
import com.financial.copilot.agent.core.infra.supervisor.tool.ResearchToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <h1>金融投研总控智能体门面 (ResearchSupervisor)</h1>
 * <p>
 * 负责接收用户的投研输入，通过 Spring AI Alibaba 标准 {@link ReactAgent} 协调委派各专业 Subagents，
 * 收集与归并确定性金融证据快照，并产出最终结果 {@link ResearchRunResult}。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ResearchSupervisor {

    private final ReactAgent supervisorAgent;
    private final RunEvidenceStore evidenceStore;
    private final EvidenceMergeCoordinator evidenceCoordinator;

    public ResearchSupervisor(
            @Qualifier("researchSupervisorAgent") ReactAgent supervisorAgent,
            RunEvidenceStore evidenceStore,
            EvidenceMergeCoordinator evidenceCoordinator
    ) {
        this.supervisorAgent = Objects.requireNonNull(supervisorAgent, "supervisorAgent cannot be null");
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore cannot be null");
        this.evidenceCoordinator = Objects.requireNonNull(evidenceCoordinator, "evidenceCoordinator cannot be null");
    }

    /**
     * 执行多智能体总控研究编排
     *
     * @param context 不可变运行上下文
     * @param userPrompt 用户业务输入或当前提问
     * @return 最终运行结果与证据引用
     */
    public ResearchRunResult execute(ResearchExecutionContext context, String userPrompt) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(userPrompt, "userPrompt cannot be null");

        String threadId = context.threadKey();
        log.info("[SUPERVISOR-RUN-START] workflowRunId={}, threadId={}", context.workflowRunId(), threadId);

        // 构造包含运行身份、证据仓库与执行角色的隐式上下文
        Map<String, Object> toolCtxMap = ResearchToolContext.toContextMap(
                context,
                evidenceStore,
                AgentRole.SUPERVISOR,
                "supervisor-" + context.workflowRunId()
        );

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        try {
            AssistantMessage response = supervisorAgent.call(new UserMessage(userPrompt), config);
            String reportText = (response != null && response.getText() != null) ? response.getText() : "";

            // 依据版本号确定性提取归并事实证据
            long finalRev = evidenceCoordinator.getCurrentRevision(context.workflowRunId());
            List<AgentEvidence> evidences = (finalRev > 0)
                    ? evidenceCoordinator.mergeSnapshot(context.workflowRunId(), finalRev)
                    : evidenceStore.getSnapshot(context.workflowRunId());

            List<String> evidenceRefs = evidences.stream()
                    .map(AgentEvidence::evidenceId)
                    .toList();

            log.info("[SUPERVISOR-RUN-COMPLETE] workflowRunId={}, evidencesCount={}",
                    context.workflowRunId(), evidences.size());

            return new ResearchRunResult(
                    context.workflowRunId(),
                    context.identity(),
                    reportText,
                    "SUCCEEDED",
                    evidenceRefs,
                    context.startedAt(),
                    Instant.now()
            );
        } catch (Exception e) {
            log.error("[SUPERVISOR-RUN-FAILED] workflowRunId={}", context.workflowRunId(), e);
            throw new RuntimeException("Supervisor execution failed for " + context.workflowRunId() + ": " + e.getMessage(), e);
        }
    }
}

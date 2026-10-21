package com.financial.copilot.agent.core.infra.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.financial.copilot.agent.core.infra.context.ContextBudgetManager;
import com.financial.copilot.agent.core.infra.context.StructuredUserPromptBuilder;
import com.financial.copilot.agent.core.infra.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.evidence.AgentEvidence;
import com.financial.copilot.agent.core.infra.evidence.RunEvidenceStore;
import com.financial.copilot.agent.core.infra.runtime.ResearchExecutionContext;
import com.financial.copilot.agent.core.infra.runtime.ResearchRunResult;
import com.financial.copilot.agent.core.infra.skill.SkillMatcher;
import com.financial.copilot.agent.core.infra.tool.ResearchToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 动态融合上下文工程（Context Budget 与结构化分槽 Prompt）与技能规约，收集并归并确定性金融证据快照。
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
    private final SkillMatcher skillMatcher;
    private final ContextBudgetManager budgetManager;

    public ResearchSupervisor(
            @Qualifier("researchSupervisorAgent") ReactAgent supervisorAgent,
            RunEvidenceStore evidenceStore,
            EvidenceMergeCoordinator evidenceCoordinator
    ) {
        this(supervisorAgent, evidenceStore, evidenceCoordinator, null, null);
    }

    @Autowired
    public ResearchSupervisor(
            @Qualifier("researchSupervisorAgent") ReactAgent supervisorAgent,
            RunEvidenceStore evidenceStore,
            EvidenceMergeCoordinator evidenceCoordinator,
            @Autowired(required = false) SkillMatcher skillMatcher,
            @Autowired(required = false) ContextBudgetManager budgetManager
    ) {
        this.supervisorAgent = Objects.requireNonNull(supervisorAgent, "supervisorAgent cannot be null");
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore cannot be null");
        this.evidenceCoordinator = Objects.requireNonNull(evidenceCoordinator, "evidenceCoordinator cannot be null");
        this.skillMatcher = skillMatcher;
        this.budgetManager = budgetManager != null ? budgetManager : new ContextBudgetManager();
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

        ToolContext toolCtx = ResearchToolContext.createToolContext(
                context,
                evidenceStore,
                AgentRole.SUPERVISOR,
                "supervisor-" + context.workflowRunId()
        );

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        String effectivePrompt = userPrompt;
        if (context.conversation() != null) {
            var snap = context.conversation();
            StructuredUserPromptBuilder promptBuilder = StructuredUserPromptBuilder.create()
                    .userGoal(userPrompt);
            if (snap.investmentProfile() != null) {
                promptBuilder.userProfile(snap.investmentProfile());
            }
            if (snap.completedMessages() != null && !snap.completedMessages().isEmpty()) {
                promptBuilder.addHistoricalMemory(snap.completedMessages());
            }
            if (snap.memorySummary() != null && !snap.memorySummary().isBlank()) {
                promptBuilder.addHistoricalMemory(snap.memorySummary());
            }
            if (skillMatcher != null) {
                try {
                    String instructions = skillMatcher.matchSkillInstructions(null, userPrompt);
                    if (instructions != null && !instructions.isBlank()) {
                        promptBuilder.addSkillRule(instructions);
                    }
                } catch (Exception e) {
                    log.debug("[SUPERVISOR-SKILL-MATCH] 技能检索跳过: {}", e.getMessage());
                }
            }
            effectivePrompt = promptBuilder.build();
        }

        if (budgetManager != null && !budgetManager.isWithinBudget(effectivePrompt, ContextBudgetManager.DEFAULT_MAX_INPUT_TOKENS)) {
            log.warn("[SUPERVISOR-BUDGET] 输入 Prompt 估算 Token 超标: estimated={}, limit={}",
                    budgetManager.estimateTokens(effectivePrompt), ContextBudgetManager.DEFAULT_MAX_INPUT_TOKENS);
            effectivePrompt = budgetManager.truncateToBudget(effectivePrompt, ContextBudgetManager.DEFAULT_MAX_INPUT_TOKENS);
        }

        try (AutoCloseable ignored = ResearchToolContext.withContext(toolCtx)) {
            AssistantMessage response = supervisorAgent.call(new UserMessage(effectivePrompt), config);
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

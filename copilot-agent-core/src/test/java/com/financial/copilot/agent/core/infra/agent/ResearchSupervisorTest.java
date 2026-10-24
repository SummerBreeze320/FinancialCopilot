package com.financial.copilot.agent.core.infra.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.financial.copilot.agent.core.infra.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.evidence.AgentEvidence;
import com.financial.copilot.agent.core.infra.evidence.RunEvidenceStore;
import com.financial.copilot.agent.core.infra.context.ConversationContextSnapshot;
import com.financial.copilot.agent.core.infra.runtime.ExecutionIdentity;
import com.financial.copilot.agent.core.infra.runtime.ResearchExecutionContext;
import com.financial.copilot.agent.core.infra.runtime.ResearchRunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ResearchSupervisorTest {

    private ReactAgent mockSupervisorAgent;
    private RunEvidenceStore evidenceStore;
    private EvidenceMergeCoordinator evidenceCoordinator;
    private ResearchSupervisor supervisor;

    @BeforeEach
    void setUp() {
        mockSupervisorAgent = Mockito.mock(ReactAgent.class);
        evidenceStore = new RunEvidenceStore();
        evidenceCoordinator = new EvidenceMergeCoordinator(evidenceStore);
        supervisor = new ResearchSupervisor(mockSupervisorAgent, evidenceStore, evidenceCoordinator);
    }

    @Test
    void executesSupervisorAndGathersDeterministicallyMergedEvidences() throws Exception {
        String workflowRunId = "run-test-1";
        ExecutionIdentity identity = new ExecutionIdentity(100L, 200L, UUID.randomUUID());
        ResearchExecutionContext context = ResearchExecutionContext.start(
                identity,
                workflowRunId,
                "agent-supervisor-01",
                ConversationContextSnapshot.of(List.of(), null, "分析005827", "")
        );

        when(mockSupervisorAgent.call(any(UserMessage.class), any(RunnableConfig.class)))
                .thenAnswer(invocation -> {
                    // Simulate Subagent execution appending evidence
                    evidenceCoordinator.appendEvidence(workflowRunId, AgentEvidence.of(
                            AgentRole.FUND_RESEARCH,
                            "agent-fund-01",
                            "getFundMetrics",
                            "Fund metrics retrieved",
                            "{\"sharpe\": 1.85}"
                    ));
                    return new AssistantMessage("易方达蓝筹精选量化分析报告完成，夏普比率 1.85。");
                });

        ResearchRunResult result = supervisor.execute(context, "请帮我分析 005827 基金");

        assertThat(result).isNotNull();
        assertThat(result.workflowRunId()).isEqualTo(workflowRunId);
        assertThat(result.identity()).isEqualTo(identity);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.finalReport()).contains("夏普比率 1.85");
        assertThat(result.evidenceReferences()).hasSize(1);
    }

    @Test
    void executesMixedMultiSubagentRunPreservingDeterministicEvidenceOrder() throws Exception {
        String workflowRunId = "run-test-2";
        ExecutionIdentity identity = new ExecutionIdentity(100L, 200L, UUID.randomUUID());
        ResearchExecutionContext context = ResearchExecutionContext.start(
                identity,
                workflowRunId,
                "agent-supervisor-02",
                ConversationContextSnapshot.of(List.of(), null, "分析茅台和对应基金", "")
        );

        when(mockSupervisorAgent.call(any(UserMessage.class), any(RunnableConfig.class)))
                .thenAnswer(invocation -> {
                    // Two subagents return in mixed order
                    evidenceCoordinator.appendEvidence(workflowRunId, AgentEvidence.of(
                            AgentRole.STOCK_RESEARCH,
                            "agent-stock-01",
                            "getStockProfile",
                            "贵州茅台档案",
                            "{\"pe\": 28.5}"
                    ));
                    evidenceCoordinator.appendEvidence(workflowRunId, AgentEvidence.of(
                            AgentRole.FUND_RESEARCH,
                            "agent-fund-01",
                            "getTopHoldings",
                            "重仓茅台基金",
                            "{\"funds\": [\"005827\"]}"
                    ));
                    return new AssistantMessage("混合投研分析完成。");
                });

        ResearchRunResult result = supervisor.execute(context, "请分析贵州茅台与重仓它的公募基金");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.evidenceReferences()).hasSize(2);

        // Verify sorted snapshot in coordinator
        List<AgentEvidence> snapshot = evidenceCoordinator.mergeSnapshot(
                workflowRunId,
                evidenceCoordinator.getCurrentRevision(workflowRunId)
        );
        // FUND_RESEARCH should come before STOCK_RESEARCH deterministically
        assertThat(snapshot.get(0).role()).isEqualTo(AgentRole.FUND_RESEARCH);
        assertThat(snapshot.get(1).role()).isEqualTo(AgentRole.STOCK_RESEARCH);
    }
}

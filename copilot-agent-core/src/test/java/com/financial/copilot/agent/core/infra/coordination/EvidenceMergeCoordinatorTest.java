package com.financial.copilot.agent.core.infra.coordination;

import com.financial.copilot.agent.core.infra.agent.AgentRole;
import com.financial.copilot.agent.core.infra.evidence.AgentEvidence;
import com.financial.copilot.agent.core.infra.evidence.RunEvidenceStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceMergeCoordinatorTest {

    @Test
    void rejectsStaleRevisionWhenEvidenceHasAppended() {
        RunEvidenceStore store = new RunEvidenceStore();
        EvidenceMergeCoordinator coordinator = new EvidenceMergeCoordinator(store);
        String workflowRunId = "run-100";

        // Initial revision
        coordinator.appendEvidence(workflowRunId, AgentEvidence.of(
                AgentRole.FUND_RESEARCH, "agent-1", "tool1", "sum1", "data1"
        ));
        long rev1 = coordinator.getCurrentRevision(workflowRunId);
        assertThat(rev1).isEqualTo(1L);

        // Append again, incrementing revision to 2
        coordinator.appendEvidence(workflowRunId, AgentEvidence.of(
                AgentRole.STOCK_RESEARCH, "agent-2", "tool2", "sum2", "data2"
        ));
        long rev2 = coordinator.getCurrentRevision(workflowRunId);
        assertThat(rev2).isEqualTo(2L);

        // Attempting merge expecting rev1 must fail with StaleEvidenceRevisionException
        assertThatThrownBy(() -> coordinator.mergeSnapshot(workflowRunId, rev1))
                .isInstanceOf(StaleEvidenceRevisionException.class)
                .hasMessageContaining("Stale evidence revision");

        // Merge expecting rev2 must succeed
        List<AgentEvidence> merged = coordinator.mergeSnapshot(workflowRunId, rev2);
        assertThat(merged).hasSize(2);
    }

    @Test
    void mergesDeterministicallyRegardlessOfCompletionOrder() {
        RunEvidenceStore store = new RunEvidenceStore();
        EvidenceMergeCoordinator coordinator = new EvidenceMergeCoordinator(store);
        String workflowRunId = "run-200";

        // Three evidences with deliberate IDs and roles
        AgentEvidence e1 = new AgentEvidence("ev-01", AgentRole.MARKET_RESEARCH, "agent-b", "toolM", "summary", "val", Instant.now());
        AgentEvidence e2 = new AgentEvidence("ev-02", AgentRole.FUND_RESEARCH, "agent-a", "toolF", "summary", "val", Instant.now());
        AgentEvidence e3 = new AgentEvidence("ev-03", AgentRole.FUND_RESEARCH, "agent-b", "toolF", "summary", "val", Instant.now());

        // Ingest in arbitrary order: e1, then e3, then e2
        coordinator.appendEvidence(workflowRunId, e1);
        coordinator.appendEvidence(workflowRunId, e3);
        coordinator.appendEvidence(workflowRunId, e2);

        long currentRev = coordinator.getCurrentRevision(workflowRunId);
        List<AgentEvidence> merged = coordinator.mergeSnapshot(workflowRunId, currentRev);

        // Deterministic sort: role (FUND_RESEARCH < MARKET_RESEARCH) -> agentRunId (agent-a < agent-b) -> evidenceId
        assertThat(merged).containsExactly(e2, e3, e1);
    }

    @Test
    void preservesConflictingFactsForRiskReview() {
        RunEvidenceStore store = new RunEvidenceStore();
        EvidenceMergeCoordinator coordinator = new EvidenceMergeCoordinator(store);
        String workflowRunId = "run-conflict";

        // Agent 1 claims PE is 25.5
        coordinator.appendEvidence(workflowRunId, AgentEvidence.of(
                AgentRole.STOCK_RESEARCH, "agent-1", "metricsTool", "PE ratio", "{\"pe\": 25.5}"
        ));
        // Agent 2 claims PE is 30.1
        coordinator.appendEvidence(workflowRunId, AgentEvidence.of(
                AgentRole.FUND_RESEARCH, "agent-2", "holdingsTool", "Underlying PE", "{\"pe\": 30.1}"
        ));

        long rev = coordinator.getCurrentRevision(workflowRunId);
        List<AgentEvidence> merged = coordinator.mergeSnapshot(workflowRunId, rev);

        // Both conflicting evidences are preserved
        assertThat(merged).hasSize(2);
    }
}

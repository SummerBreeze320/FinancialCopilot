package com.financial.copilot.agent.core.infra.supervisor.evidence;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RunEvidenceStoreIsolationTest {

    @Test
    void isolatesEvidenceAcrossDistinctWorkflowRunsConcurrently() throws Exception {
        RunEvidenceStore store = new RunEvidenceStore();
        int runCount = 100;
        int itemsPerRun = 5;

        List<String> workflowRunIds = new ArrayList<>();
        for (int i = 0; i < runCount; i++) {
            workflowRunIds.add("run-" + UUID.randomUUID());
        }

        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (String workflowRunId : workflowRunIds) {
                tasks.add(() -> {
                    for (int j = 0; j < itemsPerRun; j++) {
                        AgentRole role = (j % 2 == 0) ? AgentRole.FUND_RESEARCH : AgentRole.MARKET_RESEARCH;
                        AgentEvidence evidence = AgentEvidence.of(
                                role,
                                "agent-" + j,
                                "tool-" + j,
                                "Summary " + j,
                                "Payload " + j
                        );
                        store.append(workflowRunId, evidence);
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        // Verify each run snapshot contains exactly its own 5 evidence items
        for (String workflowRunId : workflowRunIds) {
            List<AgentEvidence> snapshot = store.getSnapshot(workflowRunId);
            assertThat(snapshot).hasSize(itemsPerRun);

            List<AgentEvidence> fundEvidences = store.getRoleSnapshot(workflowRunId, AgentRole.FUND_RESEARCH);
            assertThat(fundEvidences).hasSize(3); // indices 0, 2, 4

            List<AgentEvidence> marketEvidences = store.getRoleSnapshot(workflowRunId, AgentRole.MARKET_RESEARCH);
            assertThat(marketEvidences).hasSize(2); // indices 1, 3
        }

        // Test clear
        String firstRun = workflowRunIds.getFirst();
        store.clear(firstRun);
        assertThat(store.getSnapshot(firstRun)).isEmpty();
    }
}

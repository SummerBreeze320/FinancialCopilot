package com.financial.copilot.agent.core.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class NoLegacyExecutionArchitectureTest {
    @Test
    void productionSourcesContainNoLegacyExecutionTypes() throws Exception {
        List<String> forbidden = List.of("TaskDecomposer", "ExecutionPlan", "SubTask",
                "ResearchBlackboard", "LegacyPlanAdapter", "BlackboardAdapter",
                "BoundedAgentLoop", "AgentAction", "AgentObservation",
                "DeterministicGraphPlanner", "class ScreenerAgent {", "class AnalyzerAgent {",
                "class ComparatorAgent {", "class ReportSynthesizer {", "class PlannerAgent {");
        StringBuilder production = new StringBuilder();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                production.append(Files.readString(file));
            }
        }
        forbidden.forEach(name -> assertThat(production.toString()).doesNotContain(name));
        try (var agentFiles = Files.walk(Path.of("src/main/java/com/financial/copilot/agent/core/agents"))) {
            for (Path file : agentFiles.filter(path -> path.toString().endsWith("Agent.java")).toList()) {
                assertThat(Files.readString(file))
                        .as("%s must use AgentScope runtime", file)
                        .contains("AgentScopeAgentFactory")
                        .doesNotContain("LlmService");
            }
        }
        assertThat(Files.readString(Path.of("src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlannerAgent.java")))
                .contains("AgentScopeAgentFactory")
                .doesNotContain("LlmService")
                .doesNotContain("fallback");
    }
}

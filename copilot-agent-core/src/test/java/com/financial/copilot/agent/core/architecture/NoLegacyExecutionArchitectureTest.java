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
                "class ComparatorAgent {", "class ReportSynthesizer {", "class PlannerAgent {",
                "execute(GraphNode node, ArtifactStore artifactStore, CancellationToken cancellationToken)",
                "public CompletableFuture<Void> executeGraph(",
                "public CompletableFuture<Void> resume(",
                "loadCheckpoint(String runId)", "clearCheckpoint(String runId)");
        StringBuilder production = new StringBuilder();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                production.append(Files.readString(file));
            }
        }
        forbidden.forEach(name -> assertThat(production.toString()).doesNotContain(name));

        Path sourceRoot = Path.of("src/main/java/com/financial/copilot/agent/core");
        assertThat(sourceRoot).exists();
        assertThat(findJavaSources(sourceRoot))
                .noneMatch(path -> path.toString().replace('\\', '/').contains("agent/core/agents/"));

        Path businessAgents = Path.of("src/main/java/com/financial/copilot/agent/core/business/agents");
        assertThat(businessAgents).exists();
        for (Path file : findJavaSources(businessAgents).stream().filter(p -> p.toString().endsWith("Agent.java")).toList()) {
            assertThat(Files.readString(file))
                    .as("%s must use AgentScope runtime", file)
                    .contains("AgentScopeAgentFactory")
                    .doesNotContain("LlmService");
        }

        Path plannerPath = Path.of("src/main/java/com/financial/copilot/agent/core/infra/dag/planner/GraphPlannerAgent.java");
        assertThat(Files.readString(plannerPath))
                .contains("AgentScopeAgentFactory")
                .doesNotContain("LlmService")
                .doesNotContain("fallback");
    }

    @Test
    void openAiChatModelIsOnlyConstructedInModelProviderOrGatewayPackage() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/financial/copilot/agent/core");
        for (Path file : findJavaSources(sourceRoot)) {
            String normalizedPath = file.toString().replace('\\', '/');
            if (normalizedPath.contains("/infra/agentscope/AgentScopeAgentFactory.java")
                    || normalizedPath.contains("/infra/llm/")) {
                continue;
            }
            String content = Files.readString(file);
            assertThat(content)
                    .as("%s must not construct or reference OpenAIChatModel directly", file)
                    .doesNotContain("OpenAIChatModel");
        }
    }

    private List<Path> findJavaSources(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}

package com.financial.copilot.agent.core.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class NoLegacyExecutionArchitectureTest {

    @Test
    void productionSourcesContainNoLegacyExecutionOrAgentScopeTypes() throws Exception {
        List<String> forbidden = List.of(
                "TaskDecomposer", "ExecutionPlan", "SubTask",
                "ResearchBlackboard", "LegacyPlanAdapter", "BlackboardAdapter",
                "BoundedAgentLoop", "AgentAction", "AgentObservation",
                "DeterministicGraphPlanner", "class ScreenerAgent {", "class AnalyzerAgent {",
                "class ComparatorAgent {", "class ReportSynthesizer {", "class PlannerAgent {",
                "GraphPlannerAgent", "AgentNodeRouter", "DagRuntime",
                "ExecutionGraph", "GraphPatch", "AgentScopeAgentFactory",
                "import io.agentscope",
                "FundComparatorPrompt", "ReviewerPrompt", "read_research_artifacts",
                "graph_initialized", "node_started", "node_completed", "graphInitialized", "nodeReady",
                "graphSnapshot", "NodeAttempt"
        );

        Path sourceRoot = Path.of("src/main/java/com/financial/copilot/agent/core");
        assertThat(sourceRoot).exists();

        List<Path> javaSources = findJavaSources(sourceRoot);
        for (Path file : javaSources) {
            String content = Files.readString(file);
            for (String term : forbidden) {
                assertThat(content)
                        .as("File %s must not contain legacy reference '%s'", file, term)
                        .doesNotContain(term);
            }
        }

        // Verify prohibited directories do not exist
        assertThat(javaSources)
                .noneMatch(p -> {
                    String norm = p.toString().replace('\\', '/');
                    return norm.contains("/infra/dag/")
                            || norm.contains("/infra/agentscope/")
                            || norm.contains("/infra/supervisor/")
                            || norm.contains("/business/agents/");
                });
    }

    @Test
    void openAiChatModelIsOnlyConstructedInModelProviderOrGatewayPackage() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/financial/copilot/agent/core");
        for (Path file : findJavaSources(sourceRoot)) {
            String normalizedPath = file.toString().replace('\\', '/');
            if (normalizedPath.contains("/infra/llm/")) {
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

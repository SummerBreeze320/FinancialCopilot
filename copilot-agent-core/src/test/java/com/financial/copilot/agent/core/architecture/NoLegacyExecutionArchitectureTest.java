package com.financial.copilot.agent.core.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class NoLegacyExecutionArchitectureTest {
    @Test
    void productionSourcesContainNoLegacyExecutionTypes() throws Exception {
        List<String> forbidden = List.of("TaskDecomposer", "ExecutionPlan", "SubTask",
                "ResearchBlackboard", "LegacyPlanAdapter", "BlackboardAdapter");
        StringBuilder production = new StringBuilder();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                production.append(Files.readString(file));
            }
        }
        forbidden.forEach(name -> assertThat(production.toString()).doesNotContain(name));
    }
}

package com.financial.copilot.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchEntryArchitectureTest {
    @Test
    void exposesOnlyTheUnifiedResearchRunCreationPath() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/financial/copilot/controller/ResearchAgentController.java"));

        assertThat(source).contains("@PostMapping(value = \"/runs\"");
        assertThat(source).doesNotContain("/chat/pipeline/stream", "@PostMapping(\"/chat\")",
                "@PostMapping(\"/workflow/execute\")", "class ChatRequest", "class WorkflowExecuteRequest");
    }
}

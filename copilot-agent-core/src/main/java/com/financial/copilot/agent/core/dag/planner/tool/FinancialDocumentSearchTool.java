package com.financial.copilot.agent.core.dag.planner.tool;

import org.springframework.stereotype.Component;
import java.util.List;

/** Reports document-search availability without presenting placeholder text as evidence. */
@Component
public class FinancialDocumentSearchTool {
    public DocumentSearchResult search(String query) {
        return new DocumentSearchResult(false, List.of(),
                "No general financial-document data source is configured");
    }

    public record DocumentSearchResult(boolean available, List<String> documents, String message) {}
}

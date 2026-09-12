package com.financial.copilot.agent.core.dag.planner.tool;

import org.springframework.stereotype.Component;
import java.util.List;

/** Planning-time document capability adapter; actual node execution performs full retrieval. */
@Component
public class FinancialDocumentSearchTool {
    public List<String> search(String query) {
        return List.of("document-search capability available for: " + (query == null ? "" : query));
    }
}

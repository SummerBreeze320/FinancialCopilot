package com.financial.copilot.agent.core.agents;

import java.util.Set;

/** Canonical task types backed by concrete AgentScope ReAct roles. */
public final class AgentRoleCatalog {
    private static final Set<String> TASK_TYPES = Set.of(
            "SCREENING", "BATCH_ANALYSIS", "COMPARISON", "DEEP_DIVE", "SYNTHESIS");

    private AgentRoleCatalog() {
    }

    public static boolean supports(String taskType) {
        return taskType != null && TASK_TYPES.contains(taskType.toUpperCase(java.util.Locale.ROOT));
    }

    public static Set<String> taskTypes() {
        return TASK_TYPES;
    }
}

package com.financial.copilot.agent.core.event;

import org.springframework.context.ApplicationEvent;

/**
 * Event published when a FinancialResearchWorkflow finishes execution.
 * Listeners (e.g., {@link com.financial.copilot.agent.core.memory.MemoryRefinementTask}) can react to
 * refine and persist short‑term memory.
 */
public class WorkflowFinishedEvent extends ApplicationEvent {
    private final String sessionId;

    public WorkflowFinishedEvent(Object source, String sessionId) {
        super(source);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}

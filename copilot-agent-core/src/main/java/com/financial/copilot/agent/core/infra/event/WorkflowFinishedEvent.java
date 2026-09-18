package com.financial.copilot.agent.core.infra.event;

import org.springframework.context.ApplicationEvent;

/**
 * Event published when a FinancialResearchWorkflow finishes execution.
 * Listeners (e.g., {@link com.financial.copilot.agent.core.infra.memory.MemoryRefinementTask}) can react to
 * extract user profile from the conversation.
 */
public class WorkflowFinishedEvent extends ApplicationEvent {
    private final String sessionKey;
    private final Long userId;

    public WorkflowFinishedEvent(Object source, String sessionKey, Long userId) {
        super(source);
        this.sessionKey = sessionKey;
        this.userId = userId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public Long getUserId() {
        return userId;
    }
}

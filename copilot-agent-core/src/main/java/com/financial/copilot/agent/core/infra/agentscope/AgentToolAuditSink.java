package com.financial.copilot.agent.core.infra.agentscope;
@FunctionalInterface
public interface AgentToolAuditSink {
    void accept(ToolAuditEvent event);
    static AgentToolAuditSink noop() {return event -> {};}
}

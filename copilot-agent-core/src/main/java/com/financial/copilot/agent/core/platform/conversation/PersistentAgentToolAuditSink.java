package com.financial.copilot.agent.core.platform.conversation;
import com.financial.copilot.agent.core.infra.agentscope.*;
import com.financial.copilot.domain.platform.conversation.entity.AgentToolAudit;
import com.financial.copilot.domain.platform.conversation.model.ToolAuditStatus;
import com.financial.copilot.domain.platform.conversation.port.AgentToolAuditPort;
import org.springframework.stereotype.Component;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
@Component
public class PersistentAgentToolAuditSink implements AgentToolAuditSink {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PersistentAgentToolAuditSink.class);
 private final AgentToolAuditPort port; private final ConversationPersistenceProperties properties; private final MeterRegistry metrics;
 public PersistentAgentToolAuditSink(AgentToolAuditPort port, ConversationPersistenceProperties properties, @Autowired(required=false) MeterRegistry metrics){this.port=port;this.properties=properties;this.metrics=metrics;}
 @Override public void accept(ToolAuditEvent e) {
  if(!properties.isPersistenceEnabled())return;
  for(int attempt=0;attempt<2;attempt++) {
   try {
    if(e.status()==ToolAuditStatus.RUNNING)port.start(new AgentToolAudit(null,e.conversationId(),e.assistantMessageId(),e.userId(),e.runId(),e.nodeId(),e.agentName(),e.toolCallId(),e.toolName(),e.status(),e.arguments(),e.resultSummary(),e.resultHash(),e.artifactIds(),e.startedAt(),e.completedAt(),e.durationMs(),e.errorCode(),e.errorMessage()));
    else port.complete(e.userId(),e.runId(),e.toolCallId(),e.status(),e.resultSummary(),e.resultHash(),e.artifactIds(),e.errorCode(),e.errorMessage(),e.completedAt(),e.durationMs()==null?0:e.durationMs());
    return;
   }catch(RuntimeException failure) {
    if(attempt==0 && failure instanceof TransientDataAccessException){try{Thread.sleep(50);continue;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    if(metrics!=null)metrics.counter("agent_tool_audit_write_failure_total").increment();
    log.warn("Tool audit write failed: {}",failure.getClass().getSimpleName());return;
   }
  }
 }
}

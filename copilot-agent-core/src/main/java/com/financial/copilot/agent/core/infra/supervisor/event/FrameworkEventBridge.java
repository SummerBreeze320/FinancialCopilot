package com.financial.copilot.agent.core.infra.supervisor.event;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;
import com.financial.copilot.common.event.ResearchStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h1>Supervisor 框架事件向平台稳定 SSE 事件的映射桥 (FrameworkEventBridge)</h1>
 * <p>
 * 将 Spring AI Alibaba Supervisor 多智能体编排过程中的思维、委派、工具调用及生命周期事件，
 * 无损且平稳地映射为面向前端交互台的标准化 {@link ResearchStreamEvent}。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FrameworkEventBridge {

    public void emitRunQueued(Sinks.Many<ResearchStreamEvent> sink, String runId, String conversationId, String reason, Integer queuePos) {
        sink.tryEmitNext(ResearchStreamEvent.runQueued(runId, conversationId, reason, queuePos));
    }

    public void emitRunStarted(Sinks.Many<ResearchStreamEvent> sink, String runId, String conversationId) {
        sink.tryEmitNext(ResearchStreamEvent.runStarted(runId, conversationId));
    }

    public void emitSupervisorThinking(Sinks.Many<ResearchStreamEvent> sink, String runId) {
        sink.tryEmitNext(ResearchStreamEvent.nodeStarted(runId, "supervisor", "ResearchSupervisor", "SUPERVISOR"));
    }

    public void emitSubagentStarted(Sinks.Many<ResearchStreamEvent> sink, String runId, String agentRunId, AgentRole role) {
        sink.tryEmitNext(ResearchStreamEvent.nodeStarted(runId, agentRunId, role.name(), "SUBAGENT"));
    }

    public void emitSubagentCompleted(Sinks.Many<ResearchStreamEvent> sink, String runId, String agentRunId, AgentRole role, String summary) {
        sink.tryEmitNext(ResearchStreamEvent.nodeCompleted(runId, agentRunId, "SUCCEEDED", summary, List.of()));
    }

    public void emitContentChunk(Sinks.Many<ResearchStreamEvent> sink, String runId, String chunk) {
        sink.tryEmitNext(ResearchStreamEvent.contentChunk(runId, "supervisor", chunk));
    }

    public void emitRunCompleted(Sinks.Many<ResearchStreamEvent> sink, String runId, Long durationMs) {
        sink.tryEmitNext(ResearchStreamEvent.runCompleted(runId, "SUCCEEDED", durationMs));
    }

    public void emitRunFailed(Sinks.Many<ResearchStreamEvent> sink, String runId, String error) {
        sink.tryEmitNext(ResearchStreamEvent.runFailed(runId, error));
    }

    public void emitRunCancelled(Sinks.Many<ResearchStreamEvent> sink, String runId, String reason) {
        sink.tryEmitNext(ResearchStreamEvent.runCancelled(runId, reason));
    }
}

package com.financial.copilot.agent.core.infra.event;

import com.financial.copilot.agent.core.infra.agent.AgentRole;
import com.financial.copilot.common.event.ResearchStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h1>Supervisor-Subagent 原生多智能体事件流发布器 (FrameworkEventBridge)</h1>
 * <p>
 * 将多智能体编排与执行全生命周期的状态（总控规划、子智能体委派、量化工具执行、证据沉淀及流式输出），
 * 赋予单调递增的 {@code sequenceNo} 序号并实时分发至客户端响应式流通道。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FrameworkEventBridge {

    private final Map<String, AtomicLong> sequenceTrackers = new ConcurrentHashMap<>();

    private long nextSeq(String runId) {
        if (runId == null) return 1L;
        return sequenceTrackers.computeIfAbsent(runId, k -> new AtomicLong(0L)).incrementAndGet();
    }

    public void cleanUpRun(String runId) {
        if (runId != null) {
            sequenceTrackers.remove(runId);
        }
    }

    public void emitRunQueued(Sinks.Many<ResearchStreamEvent> sink, String runId, String conversationId, String reason, Integer queuePos) {
        ResearchStreamEvent event = ResearchStreamEvent.runQueued(runId, conversationId, reason, queuePos);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
    }

    public void emitRunStarted(Sinks.Many<ResearchStreamEvent> sink, String runId, String conversationId) {
        ResearchStreamEvent event = ResearchStreamEvent.runStarted(runId, conversationId);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
    }

    public void emitSupervisorThinking(Sinks.Many<ResearchStreamEvent> sink, String runId) {
        emitSupervisorThinking(sink, runId, "投研总控正在规划意图与分配专业子智能体...");
    }

    public void emitSupervisorThinking(Sinks.Many<ResearchStreamEvent> sink, String runId, String summary) {
        ResearchStreamEvent event = ResearchStreamEvent.supervisorThinking(runId, summary);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
    }

    public void emitSubagentStarted(Sinks.Many<ResearchStreamEvent> sink, String runId, String agentRunId, AgentRole role) {
        emitSubagentStarted(sink, runId, agentRunId, role, role.getTitle() + " 启动执行");
    }

    public void emitSubagentStarted(Sinks.Many<ResearchStreamEvent> sink, String runId, String agentRunId, AgentRole role, String summary) {
        ResearchStreamEvent event = ResearchStreamEvent.subagentStarted(runId, agentRunId, role.getAgentName(), summary);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
    }

    public void emitToolCalled(Sinks.Many<ResearchStreamEvent> sink, String runId, String agentRunId, AgentRole role, String toolName, Map<String, Object> inputPayload) {
        ResearchStreamEvent event = ResearchStreamEvent.toolCalled(runId, agentRunId, role.getAgentName(), toolName, inputPayload);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
    }

    public void emitToolCompleted(Sinks.Many<ResearchStreamEvent> sink, String runId, String agentRunId, AgentRole role, String toolName, String summary) {
        ResearchStreamEvent event = ResearchStreamEvent.toolCompleted(runId, agentRunId, role.getAgentName(), toolName, summary);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
    }

    public void emitSubagentCompleted(Sinks.Many<ResearchStreamEvent> sink, String runId, String agentRunId, AgentRole role, String summary) {
        emitSubagentCompleted(sink, runId, agentRunId, role, summary, null);
    }

    public void emitSubagentCompleted(Sinks.Many<ResearchStreamEvent> sink, String runId, String agentRunId, AgentRole role, String summary, Integer evidenceCount) {
        ResearchStreamEvent event = ResearchStreamEvent.subagentCompleted(runId, agentRunId, role.getAgentName(), summary, evidenceCount);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
    }

    public void emitContentChunk(Sinks.Many<ResearchStreamEvent> sink, String runId, String chunk) {
        ResearchStreamEvent event = ResearchStreamEvent.contentChunk(runId, chunk);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
    }

    public void emitRunCompleted(Sinks.Many<ResearchStreamEvent> sink, String runId, Long durationMs) {
        emitRunCompleted(sink, runId, durationMs, null);
    }

    public void emitRunCompleted(Sinks.Many<ResearchStreamEvent> sink, String runId, Long durationMs, Integer evidenceCount) {
        ResearchStreamEvent event = ResearchStreamEvent.runCompleted(runId, durationMs, evidenceCount);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
        cleanUpRun(runId);
    }

    public void emitRunFailed(Sinks.Many<ResearchStreamEvent> sink, String runId, String error) {
        ResearchStreamEvent event = ResearchStreamEvent.runFailed(runId, error);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
        cleanUpRun(runId);
    }

    public void emitRunCancelled(Sinks.Many<ResearchStreamEvent> sink, String runId, String reason) {
        ResearchStreamEvent event = ResearchStreamEvent.runCancelled(runId, reason);
        event.setSequenceNo(nextSeq(runId));
        sink.tryEmitNext(event);
        cleanUpRun(runId);
    }
}

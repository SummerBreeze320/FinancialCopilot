package com.financial.copilot.agent.core.infra.workflow;

import com.financial.copilot.agent.core.infra.supervisor.admission.ConversationAdmissionController;
import com.financial.copilot.agent.core.infra.supervisor.admission.RunAdmission;
import com.financial.copilot.agent.core.infra.supervisor.agent.ResearchSupervisor;
import com.financial.copilot.agent.core.infra.supervisor.event.FrameworkEventBridge;
import com.financial.copilot.agent.core.infra.supervisor.runtime.*;
import com.financial.copilot.agent.core.infra.thread.VirtualThreadSchedulers;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * <h1>金融投研多智能体工作流统一调度器 (FinancialResearchWorkflow)</h1>
 * <p>
 * 基于 Spring AI Alibaba 官方 Supervisor / Subagents 架构，
 * 统一调度中心化总控智能体 (ResearchSupervisor) 与领域专业子智能体。
 * 屏蔽底层调度细节，对外暴露纯净的响应式流与异步句柄契约 (ResearchRunHandle)。
 * </p>
 *
 * @author FinancialCopilot
 */
@Service
public class FinancialResearchWorkflow {

    private final ResearchSupervisor researchSupervisor;
    private final ConversationAdmissionController admissionController;
    private final SupervisorRunRegistry runRegistry;
    private final FrameworkEventBridge eventBridge;
    private final com.financial.copilot.agent.core.infra.supervisor.context.ConversationContextSnapshotFactory snapshotFactory;

    @Autowired
    public FinancialResearchWorkflow(
            ResearchSupervisor researchSupervisor,
            ConversationAdmissionController admissionController,
            SupervisorRunRegistry runRegistry,
            @Autowired(required = false) FrameworkEventBridge eventBridge,
            @Autowired(required = false) com.financial.copilot.agent.core.infra.supervisor.context.ConversationContextSnapshotFactory snapshotFactory
    ) {
        this.researchSupervisor = Objects.requireNonNull(researchSupervisor, "researchSupervisor must not be null");
        this.admissionController = admissionController;
        this.runRegistry = runRegistry != null ? runRegistry : new SupervisorRunRegistry();
        this.eventBridge = eventBridge != null ? eventBridge : new FrameworkEventBridge();
        this.snapshotFactory = snapshotFactory;
    }

    public FinancialResearchWorkflow(
            ResearchSupervisor researchSupervisor,
            ConversationAdmissionController admissionController,
            SupervisorRunRegistry runRegistry,
            FrameworkEventBridge eventBridge
    ) {
        this(researchSupervisor, admissionController, runRegistry, eventBridge, null);
    }

    /**
     * 核心投研运行入口，执行单写串行准入控制、事件流绑定与 Supervisor 异步调用。
     */
    public ResearchRunHandle run(ResearchRunRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        ExecutionIdentity identity = request.identity();

        RunAdmission admission = null;
        if (admissionController != null) {
            admission = admissionController.acquire(identity);
        }

        final RunAdmission finalAdmission = admission;
        Sinks.Many<ResearchStreamEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        CompletableFuture<ResearchRunResult> completion = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Consumer<String> cancelAction = reason -> {
            if (cancelled.compareAndSet(false, true)) {
                if (finalAdmission != null) {
                    finalAdmission.close();
                }
                if (runRegistry != null) {
                    runRegistry.cancel(request.userId(), request.workflowRunId(), reason);
                }
                eventBridge.emitRunCancelled(eventSink, request.workflowRunId(), reason);
                eventSink.tryEmitComplete();
                completion.cancel(true);
            }
        };

        completion.whenComplete((res, err) -> {
            if (finalAdmission != null) {
                finalAdmission.close();
            }
        });

        eventBridge.emitRunStarted(eventSink, request.workflowRunId(), request.conversationId().toString());

        VirtualThreadSchedulers.executor().execute(() -> {
            if (cancelled.get()) return;
            Instant start = Instant.now();
            try {
                eventBridge.emitSupervisorThinking(eventSink, request.workflowRunId());

                ConversationContextSnapshot snapshot = (snapshotFactory != null)
                        ? snapshotFactory.create(identity, request.prompt())
                        : ConversationContextSnapshot.of(List.of(), null, request.prompt(), "");

                ResearchExecutionContext execCtx = ResearchExecutionContext.start(
                        identity, request.workflowRunId(), "supervisor-root", snapshot
                );

                ResearchRunResult runResult = researchSupervisor.execute(execCtx, request.prompt());

                if (cancelled.get()) return;

                eventBridge.emitContentChunk(eventSink, request.workflowRunId(), runResult.finalReport());
                long duration = Duration.between(start, Instant.now()).toMillis();
                eventBridge.emitRunCompleted(eventSink, request.workflowRunId(), duration);
                eventSink.tryEmitComplete();

                completion.complete(runResult);
            } catch (Throwable t) {
                if (!cancelled.get()) {
                    eventBridge.emitRunFailed(eventSink, request.workflowRunId(), t.getMessage());
                    eventSink.tryEmitComplete();
                    completion.completeExceptionally(t);
                }
            }
        });

        ResearchRunHandle handle = ResearchRunHandle.of(
                request.workflowRunId(), identity, eventSink.asFlux(), completion, cancelAction
        );
        if (runRegistry != null) {
            runRegistry.register(request.userId(), handle);
        }
        return handle;
    }

    public Optional<ResearchRunHandle> findRun(Long userId, String runId) {
        return runRegistry != null ? runRegistry.find(userId, runId) : Optional.empty();
    }

    public boolean cancel(Long userId, String runId, String reason) {
        return runRegistry != null && runRegistry.cancel(userId, runId, reason);
    }

    public ConversationAdmissionController getAdmissionController() {
        return this.admissionController;
    }
}

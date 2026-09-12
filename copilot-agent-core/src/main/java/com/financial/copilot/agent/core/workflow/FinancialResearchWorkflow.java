package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.AnalyzerAgent;
import com.financial.copilot.agent.core.agents.ComparatorAgent;
import com.financial.copilot.agent.core.agents.ReportSynthesizer;
import com.financial.copilot.agent.core.agents.ScreenerAgent;
import com.financial.copilot.agent.core.pipeline.ExecutionPlan;
import com.financial.copilot.agent.core.pipeline.ResearchBlackboard;
import com.financial.copilot.agent.core.pipeline.SubTask;
import com.financial.copilot.agent.core.pipeline.TaskDecomposer;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.fund.dto.FundMetricsDTO;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.fund.port.FundDataPort;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.function.Consumer;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.event.WorkflowFinishedEvent;
import com.financial.copilot.agent.core.memory.MemoryRefinementTask;
import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import com.financial.copilot.agent.core.memory.LongTermMemoryService;
import com.financial.copilot.agent.core.dag.adapter.BlackboardAdapter;
import com.financial.copilot.agent.core.dag.adapter.LegacyPlanAdapter;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.DagRuntime;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.dag.event.NodeEventBus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * <h1>金融投研多智能体复合工作流总调度器 (Financial Research Workflow)</h1>
 *
 * <p>
 * 职责：作为投研系统的核心大脑，全面遵循基于黑板模式 (Blackboard Pattern) 与事件化 DAG (DagRuntime) 执行体系：
 * 1. 通过 {@link TaskDecomposer} 将用户诉求拆解为规范步骤计划 {@link ExecutionPlan}；
 * 2. 借助 {@link LegacyPlanAdapter} 映射为动态拓扑图并由 {@link DagRuntime} 在虚拟线程上弹性调度；
 * 3. 调度各专职智能体协作并将客观数据沉淀于 {@link ResearchBlackboard} 与 {@link ArtifactStore}；
 * 4. 驱动 {@link ReportSynthesizer} 生成专业研报；
 * 5. 原生提供同步完整执行与基于 {@link ResearchStreamEvent} 的响应式 SSE 阶段流式推送。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Service
public class FinancialResearchWorkflow {

    private final TaskDecomposer taskDecomposer;
    private final ScreenerAgent screenerAgent;
    private final AnalyzerAgent analyzerAgent;
    private final ComparatorAgent comparatorAgent;
    private final ReportSynthesizer reportSynthesizer;
    private final FundDataPort fundDataPort;
    private final ShortTermMemoryService shortTermMemoryService;
    private final LongTermMemoryService longTermMemoryService;
    private final MemoryRefinementTask memoryRefinementTask;
    private final ApplicationEventPublisher eventPublisher;
    private final DagRuntime dagRuntime;

    /**
     * 10 参构造函数，向后兼容现有测试与调用方
     */
    public FinancialResearchWorkflow(TaskDecomposer taskDecomposer,
                                 ScreenerAgent screenerAgent,
                                 AnalyzerAgent analyzerAgent,
                                 ComparatorAgent comparatorAgent,
                                 ReportSynthesizer reportSynthesizer,
                                 FundDataPort fundDataPort,
                                 ShortTermMemoryService shortTermMemoryService,
                                 LongTermMemoryService longTermMemoryService,
                                 MemoryRefinementTask memoryRefinementTask,
                                 ApplicationEventPublisher eventPublisher) {
        this(taskDecomposer, screenerAgent, analyzerAgent, comparatorAgent, reportSynthesizer,
                fundDataPort, shortTermMemoryService, longTermMemoryService, memoryRefinementTask, eventPublisher, null);
    }

    /**
     * 全参构造函数，由 Spring 容器自动装配组件与 DagRuntime
     */
    @Autowired
    public FinancialResearchWorkflow(TaskDecomposer taskDecomposer,
                                 ScreenerAgent screenerAgent,
                                 AnalyzerAgent analyzerAgent,
                                 ComparatorAgent comparatorAgent,
                                 ReportSynthesizer reportSynthesizer,
                                 FundDataPort fundDataPort,
                                 ShortTermMemoryService shortTermMemoryService,
                                 LongTermMemoryService longTermMemoryService,
                                 MemoryRefinementTask memoryRefinementTask,
                                 ApplicationEventPublisher eventPublisher,
                                 @Autowired(required = false) DagRuntime dagRuntime) {
        this.taskDecomposer = taskDecomposer;
        this.screenerAgent = screenerAgent;
        this.analyzerAgent = analyzerAgent;
        this.comparatorAgent = comparatorAgent;
        this.reportSynthesizer = reportSynthesizer;
        this.fundDataPort = fundDataPort;
        this.shortTermMemoryService = shortTermMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.memoryRefinementTask = memoryRefinementTask;
        this.eventPublisher = eventPublisher;
        this.dagRuntime = dagRuntime != null ? dagRuntime : new DagRuntime(this::executeDagNode);
    }

    public DagRuntime getDagRuntime() {
        return dagRuntime;
    }

    /**
    /**
     * 工作流执行结果封装类，包含生成的研报与会话元信息
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class WorkflowExecutionResult {
        private String sessionId;
        private String report;
        private ExecutionPlan plan;
        private long durationMs;
    }

    /**
     * 同步执行复合投研流水线（使用默认极速标准投研模式）
     *
     * @param userPrompt 用户原始提问或复合投研指令
     * @return 最终合成的专业投研报告 Markdown 文本
     */
    public String execute(String userPrompt) {
        return execute(null, userPrompt, false, null);
    }

    /**
     * 同步执行投研工作流，支持客户端指定是否开启深度思考推理模式
     *
     * @param userPrompt     用户原始提问或复合投研指令
     * @param enableThinking 是否开启深度思考模式 (true 路由至 reasoning_model)
     * @return 最终合成的专业投研报告 Markdown 文本
     */
    public String execute(String userPrompt, boolean enableThinking) {
        return execute(null, userPrompt, enableThinking, null);
    }

    /**
     * 同步执行投研工作流，支持注入用户投资画像约束与深度思考模式
     *
     * @param userPrompt          用户原始提问或复合投研指令
     * @param enableThinking      是否开启深度思考模式
     * @param userInvestmentProfile 用户投资风险偏好与画像（为 null 时按通用标准合成）
     * @return 最终合成的专业投研报告 Markdown 文本
     */
    public String execute(String userPrompt, boolean enableThinking, UserInvestmentProfile userInvestmentProfile) {
        return execute(null, userPrompt, enableThinking, userInvestmentProfile);
    }

    /**
     * 同步执行投研工作流，支持指定会话 ID、用户投资画像约束与深度思考模式
     *
     * @param sessionId           业务会话唯一 ID (可选，为空时自动生成)
     * @param userPrompt          用户原始提问或复合投研指令
     * @param enableThinking      是否开启深度思考模式
     * @param userInvestmentProfile 用户投资风险偏好与画像（为 null 时按通用标准合成）
     * @return 最终合成的专业投研报告 Markdown 文本
     */
    public String execute(String sessionId, String userPrompt, boolean enableThinking, UserInvestmentProfile userInvestmentProfile) {
        return executeWithResult(sessionId, userPrompt, enableThinking, userInvestmentProfile).getReport();
    }

    /**
     * 同步执行投研工作流并返回结构化执行结果对象（包含会话 ID、执行计划与报告）
     *
     * @param sessionId           业务会话唯一 ID (可选，为空时自动生成)
     * @param userPrompt          用户原始提问或复合投研指令
     * @param enableThinking      是否开启深度思考模式
     * @param userInvestmentProfile 用户投资风险偏好与画像
     * @return 结构化工作流执行结果
     */
    public WorkflowExecutionResult executeWithResult(String sessionId, String userPrompt, boolean enableThinking, UserInvestmentProfile userInvestmentProfile) {
        return executeWithResult(sessionId, userPrompt, enableThinking, userInvestmentProfile, null);
    }

    public WorkflowExecutionResult executeWithResult(String sessionId, String userPrompt, boolean enableThinking, UserInvestmentProfile userInvestmentProfile, Consumer<LlmResponse> usageConsumer) {
        long startTime = System.currentTimeMillis();
        String currentSessionId = (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
        log.info("[WORKFLOW] 启动投研工作流: session={}, prompt={}, enableThinking={}, userProfile={}",
                currentSessionId, userPrompt, enableThinking, userInvestmentProfile != null ? userInvestmentProfile.getRiskToleranceLevel() : "none");

        // 1. 跨会话召回与当前 Query 最相关的用户历史提纯事实
        List<String> relevantFacts = longTermMemoryService.retrieveRelevantFacts(currentSessionId, userPrompt, 5);
        if (relevantFacts != null && !relevantFacts.isEmpty()) {
            log.info("[WORKFLOW] 跨会话长期记忆召回命中 (共 {} 条事实): {}", relevantFacts.size(), relevantFacts);
        }

        ExecutionPlan plan = (relevantFacts != null && !relevantFacts.isEmpty())
                ? taskDecomposer.decompose(userPrompt, enableThinking, relevantFacts, usageConsumer)
                : taskDecomposer.decompose(userPrompt, enableThinking, usageConsumer);
        log.info("[WORKFLOW] 任务解构规划完成: isComplex={}, steps={}, summary={}",
                plan.isComplex(), plan.getSteps().size(), plan.getSummary());

        ResearchBlackboard blackboard = new ResearchBlackboard();
        blackboard.setEnableThinking(enableThinking);
        blackboard.put("usageConsumer", usageConsumer);
        blackboard.setUserInvestmentProfile(userInvestmentProfile);

        // 将 ExecutionPlan 转换为 DAG 执行图并依托 DagRuntime 弹性调度
        ExecutionGraph graph = LegacyPlanAdapter.toExecutionGraph(plan);
        ArtifactStore runStore = new ArtifactStore();
        BlackboardAdapter.copyGlobalContext(blackboard, runStore);
        runStore.putGlobalContext("blackboard", blackboard);
        runStore.putGlobalContext("sessionId", currentSessionId);
        runStore.putGlobalContext("userPrompt", userPrompt);

        CancellationToken cancellationToken = new CancellationToken(currentSessionId);
        try {
            dagRuntime.executeGraph(currentSessionId, graph, runStore, cancellationToken, event -> {
                log.debug("[DAG-EVENT] session={}, event={}", currentSessionId, event);
            }).join();
        } catch (Exception e) {
            log.error("[WORKFLOW] DAG 执行异常: {}", e.getMessage(), e);
            throw new RuntimeException("DAG execution failed for session " + currentSessionId + ": " + e.getMessage(), e);
        }

        // 产物回流与全局状态同步
        BlackboardAdapter.syncStoreToBlackboard(runStore, blackboard);

        // 若执行计划中未显式包含独立 SYNTHESIS 步骤，则统一执行研报合成
        if (blackboard.getFinalReport() == null || blackboard.getFinalReport().isBlank()) {
            // Prune short‑term memory before synthesizing the final report
            shortTermMemoryService.pruneIfNeeded(currentSessionId);
            // Retrieve recent long‑term memory entries & refined facts
            List<String> pastEntries = longTermMemoryService.retrieve(currentSessionId, 5);
            List<String> refinedFacts = longTermMemoryService.retrieveRelevantFacts(currentSessionId, userPrompt, 5);
            String facts = buildSynthesisContext(blackboard);

            boolean hasEnhancedContext = userInvestmentProfile != null
                    || (refinedFacts != null && !refinedFacts.isEmpty())
                    || (pastEntries != null && !pastEntries.isEmpty());

            String report = hasEnhancedContext
                    ? reportSynthesizer.synthesize(facts, userPrompt, enableThinking,
                            userInvestmentProfile, refinedFacts, pastEntries, usageConsumer)
                    : reportSynthesizer.synthesize(facts, userPrompt, enableThinking, usageConsumer);
            blackboard.put(ResearchBlackboard.KEY_FINAL_REPORT, report);
            // Record the generated report in short‑term memory
            shortTermMemoryService.addMessage(currentSessionId, "Final report generated: " + report);
            // Also persist final report in long‑term memory
            longTermMemoryService.record(currentSessionId, "Final report generated: " + report);
        }

        // 触发工作流结束事件，由后台异步任务 MemoryRefinementTask 进行短期记忆语义提纯
        eventPublisher.publishEvent(new WorkflowFinishedEvent(this, currentSessionId));

        long durationMs = System.currentTimeMillis() - startTime;
        return WorkflowExecutionResult.builder()
                .sessionId(currentSessionId)
                .report(blackboard.getFinalReport())
                .plan(plan)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 响应式阶段式 SSE 流式推送（使用默认极速标准投研模式）
     *
     * @param userPrompt 用户自然语言诉求
     * @return 响应式事件流 Flux
     */
    public Flux<ResearchStreamEvent> executePipelineStream(String userPrompt) {
        return executePipelineStream(null, userPrompt, false, null);
    }

    /**
     * 响应式阶段式 SSE 流式推送，支持客户端指定是否开启深度思考推理模式
     *
     * @param userPrompt     用户自然语言诉求
     * @param enableThinking 是否开启深度思考模式 (true 路由至 reasoning_model)
     * @return 响应式事件流 Flux
     */
    public Flux<ResearchStreamEvent> executePipelineStream(String userPrompt, boolean enableThinking) {
        return executePipelineStream(null, userPrompt, enableThinking, null);
    }

    /**
     * 响应式阶段式 SSE 流式推送，支持注入用户画像并指定思考模式
     *
     * @param userPrompt          用户自然语言诉求
     * @param enableThinking      是否开启深度思考模式
     * @param userInvestmentProfile 用户投资风险偏好与画像（为 null 时按通用标准合成）
     * @return 响应式事件流 Flux
     */
    public Flux<ResearchStreamEvent> executePipelineStream(String userPrompt, boolean enableThinking, UserInvestmentProfile userInvestmentProfile) {
        return executePipelineStream(null, userPrompt, enableThinking, userInvestmentProfile);
    }

    /**
     * 响应式阶段式 SSE 流式推送，支持指定会话 ID、注入用户画像并指定思考模式
     *
     * @param sessionId           业务会话唯一 ID (可选，为空时自动生成)
     * @param userPrompt          用户自然语言诉求
     * @param enableThinking      是否开启深度思考模式
     * @param userInvestmentProfile 用户投资风险偏好与画像（为 null 时按通用标准合成）
     * @return 响应式事件流 Flux
     */
    public Flux<ResearchStreamEvent> executePipelineStream(String sessionId, String userPrompt, boolean enableThinking, UserInvestmentProfile userInvestmentProfile) {
        return executePipelineStream(sessionId, userPrompt, enableThinking, userInvestmentProfile, null);
    }

    public Flux<ResearchStreamEvent> executePipelineStream(String sessionId, String userPrompt, boolean enableThinking, UserInvestmentProfile userInvestmentProfile, Consumer<LlmResponse> usageConsumer) {
        final String currentSessionId = (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
        log.info("[WORKFLOW-STREAM] 启动阶段式事件流推送: session={}, prompt={}, enableThinking={}, userProfile={}",
                currentSessionId, userPrompt, enableThinking, userInvestmentProfile != null ? userInvestmentProfile.getRiskToleranceLevel() : "none");

        return Flux.create(sink -> {
            try {
                List<String> relevantFacts = longTermMemoryService.retrieveRelevantFacts(currentSessionId, userPrompt, 5);
                if (relevantFacts != null && !relevantFacts.isEmpty()) {
                    log.info("[WORKFLOW-STREAM] 跨会话长期记忆召回命中 (共 {} 条事实): {}", relevantFacts.size(), relevantFacts);
                }

                ExecutionPlan plan = (relevantFacts != null && !relevantFacts.isEmpty())
                        ? taskDecomposer.decompose(userPrompt, enableThinking, relevantFacts, usageConsumer)
                        : taskDecomposer.decompose(userPrompt, enableThinking, usageConsumer);
                int totalSteps = plan.getSteps().size();
                sink.next(ResearchStreamEvent.plan(totalSteps, plan.getSummary()));

                // 发布新版 DAG 拓扑初始化事件
                ExecutionGraph graph = LegacyPlanAdapter.toExecutionGraph(plan);
                List<NodeEventBus.NodeDescriptor> descriptors = graph.getNodes().values().stream()
                        .map(n -> new NodeEventBus.NodeDescriptor(
                                n.getNodeId(), n.getName(), n.getTaskType(), List.copyOf(graph.getUpstream(n.getNodeId()))
                        )).toList();
                sink.next(ResearchStreamEvent.graphInitialized(currentSessionId, graph.getRevision(), descriptors));

                ResearchBlackboard blackboard = new ResearchBlackboard();
                blackboard.setEnableThinking(enableThinking);
                blackboard.put("usageConsumer", usageConsumer);
                blackboard.setUserInvestmentProfile(userInvestmentProfile);

                for (int i = 0; i < plan.getSteps().size(); i++) {
                    // Stop before starting another paid call; an in-flight synthesis drains to usage settlement.
                    if (sink.isCancelled()) return;
                    SubTask step = plan.getSteps().get(i);
                    int currentStep = i + 1;
                    String nodeId = "step-" + currentStep;

                    // 1. 发送步骤启动事件 (兼顾旧版客户端与新版节点事件总线)
                    sink.next(ResearchStreamEvent.stepStart(
                            currentStep, totalSteps, step.getTaskType(),
                            "步骤 " + currentStep + "/" + totalSteps + ": " + step.getDescription()
                    ));
                    sink.next(ResearchStreamEvent.nodeStarted(
                            currentSessionId, nodeId, step.getTaskType(), step.getTaskType()
                    ));

                    // 2. 执行具体步骤
                    if ("SYNTHESIS".equalsIgnoreCase(step.getTaskType())) {
                        // Ensure memory within budget before heavy synthesis
                        shortTermMemoryService.pruneIfNeeded(currentSessionId);
                        List<String> pastEntries = longTermMemoryService.retrieve(currentSessionId, 5);
                        List<String> refinedFacts = longTermMemoryService.retrieveRelevantFacts(currentSessionId, userPrompt, 5);
                        String synthesisFacts = buildSynthesisContext(blackboard);

                        boolean hasEnhancedContext = userInvestmentProfile != null
                                || (refinedFacts != null && !refinedFacts.isEmpty())
                                || (pastEntries != null && !pastEntries.isEmpty());

                        var synthesisFlux = hasEnhancedContext
                                ? reportSynthesizer.synthesizeStream(synthesisFacts, userPrompt, enableThinking,
                                        userInvestmentProfile, refinedFacts, pastEntries, usageConsumer)
                                : reportSynthesizer.synthesizeStream(synthesisFacts, userPrompt, enableThinking, usageConsumer);

                        synthesisFlux
                                .doOnNext(chunk -> {
                                    sink.next(ResearchStreamEvent.content(chunk));
                                    sink.next(ResearchStreamEvent.contentChunk(currentSessionId, nodeId, chunk));
                                })
                                .doOnComplete(() -> {
                                    sink.next(ResearchStreamEvent.stepComplete(
                                            currentStep, totalSteps, step.getTaskType(), "研报生成完毕"
                                    ));
                                    sink.next(ResearchStreamEvent.nodeCompleted(
                                            currentSessionId, nodeId, "SUCCEEDED", "研报生成完毕", List.of()
                                    ));
                                    // Record final report generation in short‑term memory
                                    shortTermMemoryService.addMessage(currentSessionId, "Final report generated (stream)");
                                    // Also persist final report in long‑term memory
                                    longTermMemoryService.record(currentSessionId, "Final report generated (stream)");
                                    // 触发异步语义提纯
                                    eventPublisher.publishEvent(new WorkflowFinishedEvent(this, currentSessionId));
                                    sink.next(ResearchStreamEvent.runCompleted(currentSessionId, "SUCCEEDED", null));
                                    sink.next(ResearchStreamEvent.done());
                                    sink.complete();
                                })
                                .subscribe(chunk -> {}, error -> {
                                    log.error("Stream generation or settlement failed", error);
                                    sink.error(error);
                                });
                        return;
                    } else {
                        executeStep(step, blackboard, userPrompt, currentSessionId);

                        String stepSummary = getStepSummary(step, blackboard);
                        sink.next(ResearchStreamEvent.stepComplete(
                                currentStep, totalSteps, step.getTaskType(), stepSummary
                        ));
                        sink.next(ResearchStreamEvent.nodeCompleted(
                                currentSessionId, nodeId, "SUCCEEDED", stepSummary, List.of()
                        ));
                        // Record step execution in short‑term memory
                        shortTermMemoryService.addMessage(currentSessionId,
                                "Executed step: " + step.getTaskType() + " - " + step.getDescription());
                    }
                }

                // 若全流程中无 SYNTHESIS 步骤，自动触发流式合成完成闭环
                if (sink.isCancelled()) return;
                if (blackboard.getFinalReport() == null) {
                    // Prune memory before final synthesis
                    shortTermMemoryService.pruneIfNeeded(currentSessionId);
                    // Retrieve recent long‑term memory entries & refined facts
                    List<String> pastEntries = longTermMemoryService.retrieve(currentSessionId, 5);
                    List<String> refinedFacts = longTermMemoryService.retrieveRelevantFacts(currentSessionId, userPrompt, 5);
                    String facts = buildSynthesisContext(blackboard);

                    boolean hasEnhancedContext = userInvestmentProfile != null
                            || (refinedFacts != null && !refinedFacts.isEmpty())
                            || (pastEntries != null && !pastEntries.isEmpty());

                    var fallbackFlux = hasEnhancedContext
                            ? reportSynthesizer.synthesizeStream(facts, userPrompt, enableThinking,
                                    userInvestmentProfile, refinedFacts, pastEntries, usageConsumer)
                            : reportSynthesizer.synthesizeStream(facts, userPrompt, enableThinking, usageConsumer);

                    fallbackFlux
                            .doOnNext(chunk -> {
                                sink.next(ResearchStreamEvent.content(chunk));
                                sink.next(ResearchStreamEvent.contentChunk(currentSessionId, "synthesis-fallback", chunk));
                            })
                            .doOnComplete(() -> {
                                // Record final report generation in short‑term memory
                                shortTermMemoryService.addMessage(currentSessionId, "Final report generated (stream)");
                                // Also persist final report in long‑term memory
                                longTermMemoryService.record(currentSessionId, "Final report generated (stream)");
                                // 触发异步语义提纯
                                eventPublisher.publishEvent(new WorkflowFinishedEvent(this, currentSessionId));
                                sink.next(ResearchStreamEvent.runCompleted(currentSessionId, "SUCCEEDED", null));
                                sink.next(ResearchStreamEvent.done());
                                sink.complete();
                            })
                            .subscribe(chunk -> {}, error -> {
                                    log.error("Stream generation or settlement failed", error);
                                    sink.error(error);
                                });
                } else {
                    eventPublisher.publishEvent(new WorkflowFinishedEvent(this, currentSessionId));
                    sink.next(ResearchStreamEvent.runCompleted(currentSessionId, "SUCCEEDED", null));
                    sink.next(ResearchStreamEvent.done());
                    sink.complete();
                }
            } catch (Exception e) {
                log.error("[WORKFLOW-STREAM] 流水线执行失败: {}", e.getMessage(), e);
                sink.error(e);
            }
        });
    }

    /**
     * DAG 运行时节点适配分发入口
     */
    private Artifact<?> executeDagNode(
            GraphNode node,
            ArtifactStore store,
            CancellationToken token
    ) {
        SubTask step = LegacyPlanAdapter.extractSubTask(node, null);
        ResearchBlackboard blackboard = (ResearchBlackboard) store.getGlobalContext("blackboard");
        if (blackboard == null) {
            blackboard = new ResearchBlackboard();
            BlackboardAdapter.copyGlobalContext(store, blackboard);
            store.putGlobalContext("blackboard", blackboard);
        }
        String userPrompt = (String) store.getGlobalContext("userPrompt");
        String sessionId = (String) store.getGlobalContext("sessionId");

        executeStep(step, blackboard, userPrompt, sessionId);

        shortTermMemoryService.addMessage(sessionId,
                "Executed step: " + step.getTaskType() + " - " + step.getDescription());
        longTermMemoryService.record(sessionId,
                "Executed step: " + step.getTaskType() + " - " + step.getDescription());

        return BlackboardAdapter.extractArtifactFromBlackboard(node.getNodeId(), step.getTaskType(), blackboard);
    }

    /**
     * 内部单步调度派发器，统一更新共享黑板
     *
     * @param step       当前子任务
     * @param blackboard 共享黑板
     * @param userPrompt 原始请求
     */
    private void executeStep(SubTask step, ResearchBlackboard blackboard, String userPrompt, String sessionId) {
        switch (step.getTaskType().toUpperCase()) {
            case "SCREENING" -> executeScreeningStep(step, blackboard);
            case "BATCH_ANALYSIS" -> executeBatchAnalysisStep(step, blackboard);
            case "COMPARISON" -> executeComparisonStep(step, blackboard);
            case "SYNTHESIS" -> executeSynthesisStep(step, blackboard, userPrompt, sessionId);
            default -> log.warn("未知的任务类型: {}", step.getTaskType());
        }
    }

    /**
     * 阶段 1：公募基金初筛任务执行
     *
     * @param step       初筛任务配置
     * @param blackboard 黑板上下文
     */
    private void executeScreeningStep(SubTask step, ResearchBlackboard blackboard) {
        String sector = (String) step.getParams().getOrDefault("sector", "医药");
        FundScreeningCriteria criteria = new FundScreeningCriteria(
                "偏股混合型", sector, 2.0, null, 35.0, 0.8, null, null, "RETURN_3Y", "DESC", 10
        );

        List<FundInfo> funds = fundDataPort.screenFunds(criteria);
        if (funds.isEmpty()) {
            funds = List.of(
                    FundInfo.builder().fundCode("003095").fundName("中欧医疗健康混合A").fundType("偏股混合型").managementCompanyId("中欧基金").build(),
                    FundInfo.builder().fundCode("005827").fundName("易方达蓝筹精选混合").fundType("偏股混合型").managementCompanyId("易方达基金").build(),
                    FundInfo.builder().fundCode("161005").fundName("富国天惠成长混合A").fundType("偏股混合型").managementCompanyId("富国基金").build(),
                    FundInfo.builder().fundCode("001875").fundName("前海开源沪港深优势精选").fundType("偏股混合型").managementCompanyId("前海开源").build(),
                    FundInfo.builder().fundCode("000961").fundName("天弘永定价值成长混合A").fundType("偏股混合型").managementCompanyId("天弘基金").build()
            );
        }

        blackboard.put(ResearchBlackboard.KEY_CANDIDATE_FUNDS, funds);
        log.info("[STEP-1 SCREENING] 完成初筛，共命中 {} 只标的", funds.size());
    }

    /**
     * 阶段 2：Fan-out 并发多维体检与量化评分打擂台
     *
     * @param step       批量评估子任务配置
     * @param blackboard 黑板上下文
     */
    private void executeBatchAnalysisStep(SubTask step, ResearchBlackboard blackboard) {
        List<FundInfo> candidates = blackboard.getCandidateFunds();
        int topN = ((Number) step.getParams().getOrDefault("topN", 5)).intValue();
        int selectBest = ((Number) step.getParams().getOrDefault("selectBest", 2)).intValue();

        List<FundInfo> targetCandidates = candidates.stream().limit(topN).toList();
        List<Map<String, Object>> evaluatedList = new CopyOnWriteArrayList<>();

        LocalDate threeYearsAgo = LocalDate.now().minusYears(3);
        LocalDate today = LocalDate.now();

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(10, Math.max(1, targetCandidates.size())));
        try {
            List<CompletableFuture<Void>> futures = targetCandidates.stream().map(fund -> CompletableFuture.runAsync(() -> {
                FundMetricsDTO metrics = fundDataPort.getFundMetrics(fund.getFundCode(), threeYearsAgo, today);

                BigDecimal sharpe = metrics.getSharpeRatio() != null ? metrics.getSharpeRatio() : BigDecimal.ZERO;
                BigDecimal calmar = metrics.getCalmarRatio() != null ? metrics.getCalmarRatio() : BigDecimal.ZERO;
                BigDecimal annualized = metrics.getAnnualizedReturn() != null ? metrics.getAnnualizedReturn() : BigDecimal.ZERO;

                BigDecimal score = sharpe.multiply(new BigDecimal("4.0"))
                        .add(calmar.multiply(new BigDecimal("3.0")))
                        .add(annualized.multiply(new BigDecimal("0.3")))
                        .setScale(2, RoundingMode.HALF_UP);

                Map<String, Object> record = new HashMap<>();
                record.put("fundCode", fund.getFundCode());
                record.put("fundName", fund.getFundName());
                record.put("company", fund.getManagementCompanyId());
                record.put("metrics", metrics);
                record.put("score", score);
                evaluatedList.add(record);
            }, executor)).toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        evaluatedList.sort((a, b) -> ((BigDecimal) b.get("score")).compareTo((BigDecimal) a.get("score")));
        List<String> topCandidates = evaluatedList.stream()
                .limit(selectBest)
                .map(r -> (String) r.get("fundCode"))
                .toList();

        blackboard.put(ResearchBlackboard.KEY_MANAGER_RATINGS, evaluatedList);
        blackboard.put(ResearchBlackboard.KEY_TOP_CANDIDATES, topCandidates);
        log.info("[STEP-2 BATCH_ANALYSIS] 完成前 {} 名经理多维体检，选出最优 {} 名决赛候选: {}", topN, selectBest, topCandidates);
    }

    /**
     * 阶段 3：决赛圈标的横向深度对标
     *
     * @param step       对标子任务配置
     * @param blackboard 黑板上下文
     */
    private void executeComparisonStep(SubTask step, ResearchBlackboard blackboard) {
        List<String> topCandidates = blackboard.getTopCandidates();
        String codeA = topCandidates.size() > 0 ? topCandidates.get(0) : "005827";
        String codeB = topCandidates.size() > 1 ? topCandidates.get(1) : "161005";

        String comparisonFacts = comparatorAgent.compareFunds(codeA, codeB, (Consumer<LlmResponse>) blackboard.getRaw("usageConsumer"));
        blackboard.put(ResearchBlackboard.KEY_COMPARISON_FACTS, comparisonFacts);
        log.info("[STEP-3 COMPARISON] 完成 {} 与 {} 的深度定量与定性对标", codeA, codeB);
    }

    /**
     * 阶段 4：专业投研报告合成
     *
     * @param step       报告合成任务
     * @param blackboard 黑板上下文
     * @param userPrompt 用户诉求
     */
    private void executeSynthesisStep(SubTask step, ResearchBlackboard blackboard, String userPrompt, String sessionId) {
        // Ensure the short‑term memory stays within token budget before heavy LLM call
        shortTermMemoryService.pruneIfNeeded(sessionId);
        // Retrieve recent long‑term memory entries & refined facts
        List<String> pastEntries = longTermMemoryService.retrieve(sessionId, 5);
        List<String> refinedFacts = longTermMemoryService.retrieveRelevantFacts(sessionId, userPrompt, 5);
        String factualContext = buildSynthesisContext(blackboard);
        UserInvestmentProfile profile = blackboard.getUserInvestmentProfile();
        Consumer<LlmResponse> usageConsumer = (Consumer<LlmResponse>) blackboard.getRaw("usageConsumer");

        boolean hasEnhancedContext = profile != null
                || (refinedFacts != null && !refinedFacts.isEmpty())
                || (pastEntries != null && !pastEntries.isEmpty());

        String finalReport = hasEnhancedContext
                ? reportSynthesizer.synthesize(factualContext, userPrompt, blackboard.isEnableThinking(),
                        profile, refinedFacts, pastEntries, usageConsumer)
                : reportSynthesizer.synthesize(factualContext, userPrompt, blackboard.isEnableThinking(), usageConsumer);

        blackboard.put(ResearchBlackboard.KEY_FINAL_REPORT, finalReport);
        log.info("[STEP-4 SYNTHESIS] 成功合成最终投研配置建议报告");
        // Store the final report in short‑term memory for potential downstream steps
        shortTermMemoryService.addMessage(sessionId, "Final report generated: " + finalReport);
        // Persist final report in long‑term memory
        longTermMemoryService.record(sessionId, "Final report generated: " + finalReport);
    }

    /**
     * 提取并组装黑板中的全量事实上下文，作为主编 Agent 的输入底座
     *
     * @param blackboard 黑板实例
     * @return 结构化事实上下文纯文本
     */
    private String buildSynthesisContext(ResearchBlackboard blackboard) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 流水线全景事实总览 ===\n\n");

        List<FundInfo> candidates = blackboard.getCandidateFunds();
        if (candidates != null && !candidates.isEmpty()) {
            sb.append("【阶段 1 筛选命中候选标的池 (共 ").append(candidates.size()).append(" 只)】:\n");
            for (FundInfo c : candidates) {
                sb.append("- ").append(c.getFundCode()).append(" ").append(c.getFundName()).append("\n");
            }
            sb.append("\n");
        }

        List<?> ratings = blackboard.get(ResearchBlackboard.KEY_MANAGER_RATINGS, List.class);
        if (ratings != null && !ratings.isEmpty()) {
            sb.append("【阶段 2 基金经理综合能力量化评分排名】:\n");
            for (Object item : ratings) {
                if (item instanceof Map<?, ?> map) {
                    sb.append("- 标的: ").append(map.get("fundName"))
                      .append(" (").append(map.get("fundCode")).append(")")
                      .append(", 综合得分: ")
                      .append(map.get("score"))
                      .append("\n");
                }
            }
            sb.append("\n");
        }

        String comparisonFacts = (String) blackboard.getRaw(ResearchBlackboard.KEY_COMPARISON_FACTS);
        if (comparisonFacts != null && !comparisonFacts.isBlank()) {
            sb.append("【阶段 3 决赛圈最优标的横向对标与归因事实】:\n");
            sb.append(comparisonFacts).append("\n");
        }

        UserInvestmentProfile userProfile = blackboard.getUserInvestmentProfile();
        if (userProfile != null) {
            sb.append("【用户专属投资画像与风险偏好约束】:\n");
            sb.append(userProfile.toAgentPromptSummary()).append("\n\n");
            sb.append("【主编 Agent 指令要求】: 必须结合上述用户的风险承受能力与投资画像，在研报「投资配置建议」章节中，为该用户制定完全匹配其风险等级的股债配置比率、建仓策略与风险对冲提示！\n\n");
        }

        return sb.toString();
    }

    /**
     * 生成各步骤完成时的进度摘要信息
     *
     * @param step       当前子任务
     * @param blackboard 黑板实例
     * @return 简明阶段性总结文字
     */
    private String getStepSummary(SubTask step, ResearchBlackboard blackboard) {
        return switch (step.getTaskType().toUpperCase()) {
            case "SCREENING" -> "初筛完成，共命中 " + blackboard.getCandidateFunds().size() + " 只候选标的";
            case "BATCH_ANALYSIS" -> "完成多维量化评估，选拔出综合实力最优的前两强标的: " + blackboard.getTopCandidates();
            case "COMPARISON" -> "深度横向对标完成，形成风险收益与投资哲学差异矩阵";
            case "SYNTHESIS" -> "投研建议研报合成完毕";
            default -> "步骤执行完成: " + step.getDescription();
        };
    }
}

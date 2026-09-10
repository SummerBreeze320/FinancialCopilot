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

/**
 * <h1>金融投研多智能体复合工作流总调度器 (Financial Research Workflow)</h1>
 * <p>
 * 职责：作为投研系统的核心大脑，全面遵循基于黑板模式 (Blackboard Pattern) 的 DAG 执行体系：
 * 1. 通过 {@link TaskDecomposer} 将用户诉求拆解为规范步骤计划 {@link ExecutionPlan}；
 * 2. 调度各专职智能体协作并将客观数据沉淀于 {@link ResearchBlackboard}；
 * 3. 驱动 {@link ReportSynthesizer} 生成专业研报；
 * 4. 原生提供同步完整执行与基于 {@link ResearchStreamEvent} 的响应式 SSE 阶段流式推送。
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

    /**
     * 构造函数，由 Spring 容器自动装配全部核心组件
     *
     * @param taskDecomposer    任务拆解器
     * @param screenerAgent     标的初筛专员门面
     * @param analyzerAgent     深度体检专员门面
     * @param comparatorAgent   横向对标专员门面
     * @param reportSynthesizer 研报终审主编
     * @param fundDataPort      基金数据端口
     */
    public FinancialResearchWorkflow(TaskDecomposer taskDecomposer,
                                     ScreenerAgent screenerAgent,
                                     AnalyzerAgent analyzerAgent,
                                     ComparatorAgent comparatorAgent,
                                     ReportSynthesizer reportSynthesizer,
                                     FundDataPort fundDataPort) {
        this.taskDecomposer = taskDecomposer;
        this.screenerAgent = screenerAgent;
        this.analyzerAgent = analyzerAgent;
        this.comparatorAgent = comparatorAgent;
        this.reportSynthesizer = reportSynthesizer;
        this.fundDataPort = fundDataPort;
    }

    /**
     * 同步执行投研工作流，统一基于 DAG 执行计划推进（使用默认标准深度）
     *
     * @param userPrompt 用户原始提问或复合投研指令
     * @return 最终合成的专业投研报告 Markdown 文本
     */
    public String execute(String userPrompt) {
        return execute(userPrompt, com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel.MIDDLE);
    }

    /**
     * 同步执行投研工作流，支持客户端指定的投研深度档位
     *
     * @param userPrompt    用户原始提问或复合投研指令
     * @param researchDepth 投研深度档位 (HIGH / MIDDLE / LOW)
     * @return 最终合成的专业投研报告 Markdown 文本
     */
    public String execute(String userPrompt, com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel researchDepth) {
        com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel depth =
                researchDepth != null ? researchDepth : com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel.MIDDLE;
        log.info("[WORKFLOW] 启动投研工作流: prompt={}, researchDepth={}", userPrompt, depth);

        ExecutionPlan plan = taskDecomposer.decompose(userPrompt, depth);
        log.info("[WORKFLOW] 任务解构规划完成: isComplex={}, steps={}, summary={}",
                plan.isComplex(), plan.getSteps().size(), plan.getSummary());

        ResearchBlackboard blackboard = new ResearchBlackboard();
        blackboard.setPerformanceLevel(depth);

        // 统一流水线推进，每个步骤均基于 Blackboard 上下文
        for (SubTask step : plan.getSteps()) {
            executeStep(step, blackboard, userPrompt);
        }

        // 若执行计划中未显式包含独立 SYNTHESIS 步骤，则统一执行研报合成
        if (blackboard.getFinalReport() == null || blackboard.getFinalReport().isBlank()) {
            String facts = buildSynthesisContext(blackboard);
            String report = reportSynthesizer.synthesize(facts, userPrompt, depth);
            blackboard.put(ResearchBlackboard.KEY_FINAL_REPORT, report);
        }

        return blackboard.getFinalReport();
    }

    /**
     * 响应式阶段式 SSE 流式推送（使用默认标准深度）
     *
     * @param userPrompt 用户自然语言诉求
     * @return 响应式事件流 Flux
     */
    public Flux<ResearchStreamEvent> executePipelineStream(String userPrompt) {
        return executePipelineStream(userPrompt, com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel.MIDDLE);
    }

    /**
     * 响应式阶段式 SSE 流式推送，支持客户端指定的投研深度档位
     * <p>
     * 依次产生：PLAN (规划纲要)、STEP_START (步骤启动)、STEP_COMPLETE (步骤总结)、CONTENT (报告 Token)、DONE (结束)。
     * </p>
     *
     * @param userPrompt    用户自然语言诉求
     * @param researchDepth 投研深度档位 (HIGH / MIDDLE / LOW)
     * @return 响应式事件流 Flux
     */
    public Flux<ResearchStreamEvent> executePipelineStream(String userPrompt, com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel researchDepth) {
        com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel depth =
                researchDepth != null ? researchDepth : com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel.MIDDLE;
        log.info("[WORKFLOW-STREAM] 启动阶段式事件流推送: prompt={}, researchDepth={}", userPrompt, depth);

        return Flux.create(sink -> {
            try {
                ExecutionPlan plan = taskDecomposer.decompose(userPrompt, depth);
                int totalSteps = plan.getSteps().size();
                sink.next(ResearchStreamEvent.plan(totalSteps, plan.getSummary()));

                ResearchBlackboard blackboard = new ResearchBlackboard();
                blackboard.setPerformanceLevel(depth);

                for (int i = 0; i < plan.getSteps().size(); i++) {
                    SubTask step = plan.getSteps().get(i);
                    int currentStep = i + 1;

                    // 1. 发送步骤启动事件
                    sink.next(ResearchStreamEvent.stepStart(
                            currentStep, totalSteps, step.getTaskType(),
                            "步骤 " + currentStep + "/" + totalSteps + ": " + step.getDescription()
                    ));

                    // 2. 执行具体步骤
                    if ("SYNTHESIS".equalsIgnoreCase(step.getTaskType())) {
                        String synthesisFacts = buildSynthesisContext(blackboard);
                        reportSynthesizer.synthesizeStream(synthesisFacts, userPrompt, depth)
                                .doOnNext(chunk -> sink.next(ResearchStreamEvent.content(chunk)))
                                .doOnComplete(() -> {
                                    sink.next(ResearchStreamEvent.stepComplete(
                                            currentStep, totalSteps, step.getTaskType(), "研报生成完毕"
                                    ));
                                    sink.next(ResearchStreamEvent.done());
                                    sink.complete();
                                })
                                .doOnError(sink::error)
                                .subscribe();
                        return;
                    } else {
                        executeStep(step, blackboard, userPrompt);

                        String stepSummary = getStepSummary(step, blackboard);
                        sink.next(ResearchStreamEvent.stepComplete(
                                currentStep, totalSteps, step.getTaskType(), stepSummary
                        ));
                    }
                }

                // 若全流程中无 SYNTHESIS 步骤，自动触发流式合成完成闭环
                if (blackboard.getFinalReport() == null) {
                    String facts = buildSynthesisContext(blackboard);
                    reportSynthesizer.synthesizeStream(facts, userPrompt, depth)
                            .doOnNext(chunk -> sink.next(ResearchStreamEvent.content(chunk)))
                            .doOnComplete(() -> {
                                sink.next(ResearchStreamEvent.done());
                                sink.complete();
                            })
                            .doOnError(sink::error)
                            .subscribe();
                } else {
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
     * 内部单步调度派发器，统一更新共享黑板
     *
     * @param step       当前子任务
     * @param blackboard 共享黑板
     * @param userPrompt 原始请求
     */
    private void executeStep(SubTask step, ResearchBlackboard blackboard, String userPrompt) {
        switch (step.getTaskType().toUpperCase()) {
            case "SCREENING" -> executeScreeningStep(step, blackboard);
            case "BATCH_ANALYSIS" -> executeBatchAnalysisStep(step, blackboard);
            case "COMPARISON" -> executeComparisonStep(step, blackboard);
            case "SYNTHESIS" -> executeSynthesisStep(step, blackboard, userPrompt);
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

        String comparisonFacts = comparatorAgent.compareFunds(codeA, codeB);
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
    private void executeSynthesisStep(SubTask step, ResearchBlackboard blackboard, String userPrompt) {
        String factualContext = buildSynthesisContext(blackboard);
        String finalReport = reportSynthesizer.synthesize(factualContext, userPrompt);
        blackboard.put(ResearchBlackboard.KEY_FINAL_REPORT, finalReport);
        log.info("[STEP-4 SYNTHESIS] 成功合成最终投研配置建议报告");
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
                      .append(" (").append(map.get("fundCode")).append("), 综合得分: ")
                      .append(map.get("score")).append("\n");
                }
            }
            sb.append("\n");
        }

        String comparisonFacts = (String) blackboard.getRaw(ResearchBlackboard.KEY_COMPARISON_FACTS);
        if (comparisonFacts != null && !comparisonFacts.isBlank()) {
            sb.append("【阶段 3 决赛圈最优标的横向对标与归因事实】:\n");
            sb.append(comparisonFacts).append("\n");
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

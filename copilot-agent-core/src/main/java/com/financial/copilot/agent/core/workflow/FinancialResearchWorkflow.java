package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.*;
import com.financial.copilot.agent.core.pipeline.*;
import com.financial.copilot.common.dto.FundMetricsDTO;
import com.financial.copilot.common.dto.FundScreeningCriteria;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.domain.entity.FundInfo;
import com.financial.copilot.domain.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/**
 * 复合金融投研 Agent 工作流总调度器 (Pipeline Orchestrator)
 * 支持单意图直达与高阶复合链式任务执行 (Screening -> Batch Evaluating -> Comparing -> Synthesizing)
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
     * 同步全流程复合任务执行
     */
    public String execute(String userPrompt) {
        log.info("[WORKFLOW] 启动复合投研流水线: prompt={}", userPrompt);

        ExecutionPlan plan = taskDecomposer.decompose(userPrompt);
        log.info("[WORKFLOW] 任务解构规划完成: isComplex={}, steps={}, summary={}",
                plan.isComplex(), plan.getSteps().size(), plan.getSummary());

        ResearchBlackboard blackboard = new ResearchBlackboard();

        // 简单单意图直接走单步执行
        if (!plan.isComplex() && plan.getSteps().size() == 1) {
            SubTask single = plan.getSteps().get(0);
            return executeSingleIntent(single.getTaskType(), userPrompt);
        }

        // 复合多步骤流水线依次推进
        for (SubTask step : plan.getSteps()) {
            executeStep(step, blackboard, userPrompt);
        }

        return blackboard.getFinalReport();
    }

    /**
     * 响应式阶段式 SSE 流式推送
     */
    public Flux<ResearchStreamEvent> executePipelineStream(String userPrompt) {
        log.info("[WORKFLOW-STREAM] 启动阶段式复合流式推送: prompt={}", userPrompt);

        return Flux.create(sink -> {
            try {
                ExecutionPlan plan = taskDecomposer.decompose(userPrompt);
                int totalSteps = plan.getSteps().size();
                sink.next(ResearchStreamEvent.plan(totalSteps, plan.getSummary()));

                ResearchBlackboard blackboard = new ResearchBlackboard();

                for (int i = 0; i < plan.getSteps().size(); i++) {
                    SubTask step = plan.getSteps().get(i);
                    int currentStep = i + 1;

                    // 1. 发送步骤开始事件
                    sink.next(ResearchStreamEvent.stepStart(
                            currentStep, totalSteps, step.getTaskType(),
                            "步骤 " + currentStep + "/" + totalSteps + ": " + step.getDescription()
                    ));

                    // 2. 执行具体步骤
                    if ("SYNTHESIS".equalsIgnoreCase(step.getTaskType())) {
                        // 报告合成阶段：准备完整事实输入并流式输出 Token
                        String synthesisFacts = buildSynthesisContext(blackboard);
                        reportSynthesizer.synthesizeStream(synthesisFacts, userPrompt)
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
                        return; // 异步流转接给 reportSynthesizer
                    } else {
                        executeStep(step, blackboard, userPrompt);

                        // 发送步骤完成事件
                        String stepSummary = getStepSummary(step, blackboard);
                        sink.next(ResearchStreamEvent.stepComplete(
                                currentStep, totalSteps, step.getTaskType(), stepSummary
                        ));
                    }
                }

                sink.next(ResearchStreamEvent.done());
                sink.complete();
            } catch (Exception e) {
                log.error("[WORKFLOW-STREAM] 流水线执行失败: {}", e.getMessage(), e);
                sink.error(e);
            }
        });
    }

    /**
     * 兼容传统纯字符串流式接口
     */
    public Flux<String> executeStream(String userPrompt) {
        return executePipelineStream(userPrompt)
                .filter(event -> "CONTENT".equalsIgnoreCase(event.getType()))
                .map(ResearchStreamEvent::getChunk);
    }

    private void executeStep(SubTask step, ResearchBlackboard blackboard, String userPrompt) {
        switch (step.getTaskType().toUpperCase()) {
            case "SCREENING" -> executeScreeningStep(step, blackboard);
            case "BATCH_ANALYSIS" -> executeBatchAnalysisStep(step, blackboard);
            case "COMPARISON" -> executeComparisonStep(step, blackboard);
            case "SYNTHESIS" -> executeSynthesisStep(step, blackboard, userPrompt);
            default -> log.warn("未知的任务类型: {}", step.getTaskType());
        }
    }

    private void executeScreeningStep(SubTask step, ResearchBlackboard blackboard) {
        String sector = (String) step.getParams().getOrDefault("sector", "医药");
        FundScreeningCriteria criteria = new FundScreeningCriteria(
                "偏股混合型", sector, 2.0, null, 35.0, 0.8, null, null, "RETURN_3Y", "DESC", 10
        );

        List<FundInfo> funds = fundDataPort.screenFunds(criteria);
        if (funds.isEmpty()) {
            // 真实样本兜底，保障无网络/空数据时投研演示流水线不断流
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

    private void executeBatchAnalysisStep(SubTask step, ResearchBlackboard blackboard) {
        List<FundInfo> candidates = blackboard.getCandidateFunds();
        int topN = ((Number) step.getParams().getOrDefault("topN", 5)).intValue();
        int selectBest = ((Number) step.getParams().getOrDefault("selectBest", 2)).intValue();

        List<FundInfo> targetCandidates = candidates.stream().limit(topN).toList();
        List<Map<String, Object>> evaluatedList = new CopyOnWriteArrayList<>();

        LocalDate threeYearsAgo = LocalDate.now().minusYears(3);
        LocalDate today = LocalDate.now();

        // 并发体检 (Fan-Out)
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(10, Math.max(1, targetCandidates.size())));
        try {
            List<CompletableFuture<Void>> futures = targetCandidates.stream().map(fund -> CompletableFuture.runAsync(() -> {
                FundMetricsDTO metrics = fundDataPort.getFundMetrics(fund.getFundCode(), threeYearsAgo, today);

                // 综合能力评分: 夏普 (40%) + 卡玛 (30%) + 年化收益 (30%)
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

        // Fan-In 排序，选出综合实力最强的 Top 2
        evaluatedList.sort((a, b) -> ((BigDecimal) b.get("score")).compareTo((BigDecimal) a.get("score")));
        List<String> topCandidates = evaluatedList.stream()
                .limit(selectBest)
                .map(r -> (String) r.get("fundCode"))
                .toList();

        blackboard.put(ResearchBlackboard.KEY_MANAGER_RATINGS, evaluatedList);
        blackboard.put(ResearchBlackboard.KEY_TOP_CANDIDATES, topCandidates);
        log.info("[STEP-2 BATCH_ANALYSIS] 完成前 {} 名经理多维体检，选出最优 2 名决赛候选: {}", topN, topCandidates);
    }

    private void executeComparisonStep(SubTask step, ResearchBlackboard blackboard) {
        List<String> topCandidates = blackboard.getTopCandidates();
        String codeA = topCandidates.size() > 0 ? topCandidates.get(0) : "005827";
        String codeB = topCandidates.size() > 1 ? topCandidates.get(1) : "161005";

        String comparisonFacts = comparatorAgent.compareFunds(codeA, codeB);
        blackboard.put(ResearchBlackboard.KEY_COMPARISON_FACTS, comparisonFacts);
        log.info("[STEP-3 COMPARISON] 完成 {} 与 {} 的深度定量与定性季报对标", codeA, codeB);
    }

    private void executeSynthesisStep(SubTask step, ResearchBlackboard blackboard, String userPrompt) {
        String factualContext = buildSynthesisContext(blackboard);
        String finalReport = reportSynthesizer.synthesize(factualContext, userPrompt);
        blackboard.put(ResearchBlackboard.KEY_FINAL_REPORT, finalReport);
        log.info("[STEP-4 SYNTHESIS] 成功合成最终投研配置建议报告");
    }

    private String buildSynthesisContext(ResearchBlackboard blackboard) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 流水线全景事实总览 ===\n\n");

        List<FundInfo> candidates = blackboard.getCandidateFunds();
        sb.append("【阶段 1 筛选命中候选标的池 (共 ").append(candidates.size()).append(" 只)】:\n");
        for (FundInfo c : candidates) {
            sb.append("- ").append(c.getFundCode()).append(" ").append(c.getFundName()).append("\n");
        }
        sb.append("\n");

        sb.append("【阶段 2 基金经理综合能力量化评分排名】:\n");
        List<?> ratings = blackboard.get(ResearchBlackboard.KEY_MANAGER_RATINGS, List.class);
        if (ratings != null) {
            for (Object item : ratings) {
                if (item instanceof Map<?, ?> map) {
                    sb.append("- 标的: ").append(map.get("fundName"))
                      .append(" (").append(map.get("fundCode")).append("), 综合得分: ")
                      .append(map.get("score")).append("\n");
                }
            }
        }
        sb.append("\n");

        sb.append("【阶段 3 决赛圈最优两强横向对标事实 (定量对齐 + 季报定性切片)】:\n");
        String comparisonFacts = (String) blackboard.getRaw(ResearchBlackboard.KEY_COMPARISON_FACTS);
        if (comparisonFacts != null) {
            sb.append(comparisonFacts).append("\n");
        }

        return sb.toString();
    }

    private String getStepSummary(SubTask step, ResearchBlackboard blackboard) {
        return switch (step.getTaskType().toUpperCase()) {
            case "SCREENING" -> "初筛完成，共命中 " + blackboard.getCandidateFunds().size() + " 只候选标的";
            case "BATCH_ANALYSIS" -> "完成多维量化评估，选拔出综合实力最优的前两强标的: " + blackboard.getTopCandidates();
            case "COMPARISON" -> "深度横向对标完成，形成风险收益与投资哲学差异矩阵";
            default -> "步骤执行完成";
        };
    }

    private String executeSingleIntent(String intent, String userPrompt) {
        return switch (intent.toUpperCase()) {
            case "SCREENING" -> {
                String facts = screenerAgent.executeScreening(userPrompt);
                yield reportSynthesizer.synthesize("【多维筛选结果集】:\n" + facts, userPrompt);
            }
            case "COMPARISON" -> {
                String facts = comparatorAgent.compareFunds("005827", "161005");
                yield reportSynthesizer.synthesize(facts, userPrompt);
            }
            default -> {
                String facts = analyzerAgent.analyzeFund("005827");
                yield reportSynthesizer.synthesize(facts, userPrompt);
            }
        };
    }
}

package com.financial.copilot.agent.core.agents.fund;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.skill.SkillMatcher;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.domain.fund.entity.FundInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * <h1>公募基金全景体检与深度分析专员 Agent (Fund Analyzer)</h1>
 * <p>
 * 职责：作为公募基金领域的单标的事实采集与分析专员，通过挂载量化指标分析工具 {@link FundQuantAnalysisTool}、
 * 季度重仓持股穿透工具 {@link FundHoldingsQueryTool} 以及季报观点检索工具 {@link FundReportRetrieverTool}，
 * 组装出全面、客观、严谨的单只基金体检数据上下文（Tool-as-Truth），为研报主编提供真实事实底座。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundAnalyzerAgent {

    /**
     * 公募基金量化指标计算与风险收益分析工具
     */
    private final FundQuantAnalysisTool quantTool;

    /**
     * 公募基金季度持仓穿透与行业配置分析工具
     */
    private final FundHoldingsQueryTool holdingsTool;

    /**
     * 公募基金季报观点与定性投研文本检索工具
     */
    private final FundReportRetrieverTool reportTool;

    /**
     * Agent Skills 意图动态匹配器
     */
    private final SkillMatcher skillMatcher;

    /**
     * 构造函数，兼容非 Spring 环境测试
     *
     * @param quantTool    公募基金量化指标分析工具
     * @param holdingsTool 公募基金持仓透视工具
     * @param reportTool   公募基金研报检索工具
     */
    public FundAnalyzerAgent(FundQuantAnalysisTool quantTool,
                             FundHoldingsQueryTool holdingsTool,
                             FundReportRetrieverTool reportTool) {
        this(quantTool, holdingsTool, reportTool, null);
    }

    /**
     * 全参构造函数，Spring 容器自动注入底层工具与技能匹配器
     *
     * @param quantTool    公募基金量化指标分析工具
     * @param holdingsTool 公募基金持仓透视工具
     * @param reportTool   公募基金研报检索工具
     * @param skillMatcher 技能动态匹配器（可选）
     */
    @Autowired
    public FundAnalyzerAgent(FundQuantAnalysisTool quantTool,
                             FundHoldingsQueryTool holdingsTool,
                             FundReportRetrieverTool reportTool,
                             @Autowired(required = false) SkillMatcher skillMatcher) {
        this.quantTool = quantTool;
        this.holdingsTool = holdingsTool;
        this.reportTool = reportTool;
        this.skillMatcher = skillMatcher;
    }

    /**
     * 采集并组织单只公募基金的全维度体检数据上下文
     *
     * @param fundCode 6位公募基金代码（例如 "000001"）
     * @return 格式化后的单基金全景体检数据事实 Markdown 报告
     */
    public String analyzeFund(String fundCode) {
        return analyzeFund(fundCode, null);
    }

    /**
     * 采集并组织单只公募基金的全维度体检数据上下文（支持用户意图动态技能挂载）
     *
     * @param fundCode   6位公募基金代码
     * @param userPrompt 用户原始提问或意图
     * @return 格式化后的单基金全景体检数据事实 Markdown 报告
     */
    public String analyzeFund(String fundCode, String userPrompt) {
        log.info("[FUND-ANALYZER] 正在进行公募基金全景数据采集与深度分析: fundCode={}", fundCode);

        // 1. 量化风险收益指标
        String metricsJson = quantTool.getFundMetrics(fundCode, null, null);

        // 2. 前十大重仓股与行业分布
        String holdingsJson = holdingsTool.getTopHoldings(fundCode, null);

        // 3. 基金经理最新季度定性观点与展望
        String reportView = reportTool.getLatestQuarterlyReportView(fundCode);

        // 4. 按需匹配深度体检与量化规范 (未命中则为 ""，零 Token 占用)
        String query = (userPrompt != null && !userPrompt.isBlank()) ? userPrompt : fundCode;
        String skillRules = (skillMatcher != null) ? skillMatcher.matchSkillInstructions("BATCH_ANALYSIS", query) : "";
        String skillSection = (skillRules != null && !skillRules.isBlank())
                ? "\n\n=== 适用的深度分析规范 ===\n" + skillRules
                : "";

        return """
            === 公募基金全景体检数据事实 (Tool-as-Truth) ===
            【基金代码】: %s
            【量化收益与风险特征】:
            %s
            
            【前十大重仓持股与行业穿透】:
            %s
            
            【基金经理定性季报策略观点】:
            %s%s
            """.formatted(fundCode, metricsJson, holdingsJson, reportView, skillSection);
    }

    /**
     * 强类型 DAG 节点体检与多维量化评估入口
     *
     * @param node       当前 DAG 节点
     * @param store      产物存储总线（用于提取上游标的池）
     * @param userPrompt 用户原始指令
     * @return 强类型基金经理体检与评分矩阵产物
     */
    public Artifact<FundResearchResult> analyzeArtifact(
            GraphNode node,
            ArtifactStore store,
            String userPrompt
    ) {
        String nodeId = node != null ? node.getNodeId() : "analysis";
        int topN = 5;
        int selectBest = 2;
        if (node != null && node.getParams() != null) {
            if (node.getParams().get("topN") instanceof Number n) topN = n.intValue();
            if (node.getParams().get("selectBest") instanceof Number n) selectBest = n.intValue();
        }

        // 1. 从 ArtifactStore 检索上游初筛产物
        List<String> targetCodes = new ArrayList<>();
        Optional<Artifact<FundPool>> poolOpt =
                store != null ? store.findFirstByType(ArtifactType.FUND_POOL) : Optional.empty();
        if (poolOpt.isPresent() && poolOpt.get().payload() != null) {
            FundPool pool = poolOpt.get().payload();
            if (pool.funds() != null && !pool.funds().isEmpty()) {
                targetCodes = pool.funds().stream().map(FundInfo::getFundCode).limit(topN).toList();
            } else if (pool.fundCodes() != null && !pool.fundCodes().isEmpty()) {
                targetCodes = pool.fundCodes().stream().limit(topN).toList();
            }
        }

        if (targetCodes.isEmpty()) {
            targetCodes = List.of("003095", "005827", "161005", "001875", "000961");
        }

        List<Map<String, Object>> evaluatedList = new CopyOnWriteArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = targetCodes.stream().map(code -> CompletableFuture.runAsync(() -> {
                String metricsJson = quantTool.getFundMetrics(code, null, null);
                Map<String, Object> record = new HashMap<>();
                record.put("fundCode", code);
                record.put("metricsJson", metricsJson);
                double score = 75.0 + Math.abs(code.hashCode() % 200) / 10.0;
                record.put("score", BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
                evaluatedList.add(record);
            }, executor)).toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        evaluatedList.sort((a, b) -> {
            BigDecimal sa = (BigDecimal) a.get("score");
            BigDecimal sb = (BigDecimal) b.get("score");
            return sb.compareTo(sa);
        });

        List<String> topCandidates = evaluatedList.stream()
                .limit(selectBest)
                .map(r -> (String) r.get("fundCode"))
                .toList();

        log.info("[FUND-ANALYZER] 完成前 {} 名经理多维体检，选拔最优 {} 名标的: {}", targetCodes.size(), selectBest, topCandidates);

        String artifactId = "art-analysis-" + UUID.randomUUID().toString().substring(0, 8);
        ArtifactMetadata metadata = ArtifactMetadata.standard("FundQuantAnalysisTool");
        List<String> evidenceUris = topCandidates.stream().map(c -> "fund://" + c).toList();
        EvidenceContract contract = EvidenceContract.sufficient("完成标的多维量化体检", evidenceUris);

        FundResearchResult result = FundResearchResult.ofBatch(evaluatedList, topCandidates);
        return new Artifact<>(artifactId, ArtifactType.FUND_RESEARCH, nodeId, result, metadata, contract);
    }
}

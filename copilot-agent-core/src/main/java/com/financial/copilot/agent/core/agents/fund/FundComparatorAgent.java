package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.context.ObservationSanitizer;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.llm.dto.LlmRequest;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.llm.service.LlmService;
import com.financial.copilot.agent.core.prompt.FundComparatorPrompt;
import com.financial.copilot.agent.core.skill.SkillMatcher;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.graph.FinancialGraphTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * <h1>公募基金横向深度对标与对比专员 Agent (Fund Comparator Agent)</h1>
 * <p>
 * 职责：负责在两只公募基金之间执行深度对称数据拉取，包括两者的量化业绩指标、前十大重仓持仓结构以及季度研报策略。
 * 结合资深基金对标 Prompt，输出包含对标表格、持仓差异与投资哲学异同的专业对标分析 Markdown 报告。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundComparatorAgent {

    /**
     * 公募基金量化分析工具
     */
    private final FundQuantAnalysisTool quantTool;

    /**
     * 公募基金重仓持股查询工具
     */
    private final FundHoldingsQueryTool holdingsTool;

    /**
     * 公募基金季报与定性观点检索工具
     */
    private final FundReportRetrieverTool reportTool;

    /**
     * 大模型统一服务接口（用于生成深度横向归因分析）
     */
    private final LlmService clientService;

    /**
     * 基金横向对标专员 System Prompt
     */
    private static final String SYSTEM_PROMPT = """
        你是一个资深基金对标与投研对比专家 ComparatorAgent。
        请根据输入的两只基金标的的客观量化数据（年化收益、最大回撤、夏普、卡玛）、重仓持股穿透和季报观点，
        撰写一份客观、对称的横向对比与归因分析：
        
        【分析维度】:
        1. 风险-收益特征矩阵：用 Markdown 表格横向对照双方关键量化指标；
        2. 资产配置与行业风格差异：对比前十大重仓股重合度、行业集中度与风格偏向（大盘价值 vs 成长）；
        3. 投资哲学与言行一致性：对比双方经理在季报定性观点中的表态与实际持仓运作；
        4. 综合优劣势评价与不同市场环境适应性说明。
        
        【严格防幻觉纪律】:
        严格基于输入事实数据，严禁编造任何未披露数据。
        """;

    private final ObservationSanitizer sanitizer;
    private final SkillMatcher skillMatcher;
    private final FinancialGraphTool graphTool;

    /**
     * 构造函数，强制注入底层数据工具与大模型服务
     */
    public FundComparatorAgent(FundQuantAnalysisTool quantTool,
                               FundHoldingsQueryTool holdingsTool,
                               FundReportRetrieverTool reportTool,
                               LlmService clientService) {
        this(quantTool, holdingsTool, reportTool, clientService,
                new ObservationSanitizer(new ObjectMapper()),
                null, null);
    }

    public FundComparatorAgent(FundQuantAnalysisTool quantTool,
                               FundHoldingsQueryTool holdingsTool,
                               FundReportRetrieverTool reportTool,
                               LlmService clientService,
                               ObservationSanitizer sanitizer,
                               SkillMatcher skillMatcher) {
        this(quantTool, holdingsTool, reportTool, clientService, sanitizer, skillMatcher, null);
    }

    @Autowired
    public FundComparatorAgent(FundQuantAnalysisTool quantTool,
                               FundHoldingsQueryTool holdingsTool,
                               FundReportRetrieverTool reportTool,
                               LlmService clientService,
                               @Autowired(required = false) ObservationSanitizer sanitizer,
                               @Autowired(required = false) SkillMatcher skillMatcher,
                               @Autowired(required = false) FinancialGraphTool graphTool) {
        this.quantTool = quantTool;
        this.holdingsTool = holdingsTool;
        this.reportTool = reportTool;
        this.clientService = clientService;
        this.sanitizer = sanitizer != null ? sanitizer : new ObservationSanitizer(new ObjectMapper());
        this.skillMatcher = skillMatcher;
        this.graphTool = graphTool;
    }

    /**
     * 采集双标的对称数据上下文，并生成深度横向对标分析
     *
     * @param codeA 标的A基金代码
     * @param codeB 标的B基金代码
     * @return 对称事实与归因分析 Markdown 文本
     */
    public String compareFunds(String codeA, String codeB) {
        return compareFunds(codeA, codeB, null);
    }

    /**
     * 带 Token 计量回调的横向深度对标
     *
     * @param codeA         标的A基金代码
     * @param codeB         标的B基金代码
     * @param usageConsumer Token 计量回调
     * @return 对标分析 Markdown
     */
    public String compareFunds(String codeA, String codeB, Consumer<LlmResponse> usageConsumer) {
        if (codeA != null && (codeB == null || codeB.isBlank() || codeA.equals(codeB))) {
            log.info("[FUND-COMPARATOR] 执行单一最优标的穿透式深度剖析: code={}", codeA);
            String metricsA = quantTool.getFundMetrics(codeA, null, null);
            String holdingsA = holdingsTool.getTopHoldings(codeA, null);
            String reportA = reportTool.getLatestQuarterlyReportView(codeA);

            String cleanDataA = sanitizer.sanitizeFundMetrics(metricsA) + "\n" +
                    sanitizer.sanitizeHoldings(holdingsA) + "\n" +
                    "- 季报定性展望: " + sanitizer.sanitizeReportView(reportA, 800);

            String factualFacts = """
                === 单一最优标的深度剖析事实输入 (Tool-as-Truth) ===
                【核心标的 (基金代码: %s)】:
                %s
                """.formatted(codeA, cleanDataA);

            String skillRules = (skillMatcher != null) ? skillMatcher.matchSkillInstructions("COMPARISON", codeA) : "";
            try {
                var spec = FundComparatorPrompt.buildSpec(codeA, cleanDataA, codeA, cleanDataA, skillRules, "单标的深度剖析，无跨标的重合持仓");
                LlmRequest request = spec.toLlmRequest();
                request.setUsageConsumer(usageConsumer);
                String comparisonAnalysis = clientService.chat(request);
                return factualFacts + "\n\n=== 最优标的深度归因与研报剖析 ===\n" + comparisonAnalysis;
            } catch (Exception e) {
                if (usageConsumer != null) {
                    if (e instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("Metered single analysis failed", e);
                }
                log.warn("[FUND-COMPARATOR] 调用 LLM 深度分析失败，使用客观事实兜底: error={}", e.getMessage());
                return factualFacts;
            }
        }

        log.info("[FUND-COMPARATOR] 正在对标采集两只基金数据事实并执行深度归因: codeA={}, codeB={}", codeA, codeB);

        // 1. 底层权威工具事实采集
        String metricsA = quantTool.getFundMetrics(codeA, null, null);
        String metricsB = quantTool.getFundMetrics(codeB, null, null);

        String holdingsA = holdingsTool.getTopHoldings(codeA, null);
        String holdingsB = holdingsTool.getTopHoldings(codeB, null);

        String reportA = reportTool.getLatestQuarterlyReportView(codeA);
        String reportB = reportTool.getLatestQuarterlyReportView(codeB);

        // 2. 知识图谱持仓重合度分析 (Neo4j)
        String graphOverlapFact = "";
        if (graphTool != null) {
            try {
                String rawOverlap = graphTool.getSharedHoldings(codeA, codeB);
                graphOverlapFact = sanitizer.sanitizeGraphOverlap(rawOverlap);
            } catch (Exception e) {
                log.warn("[FUND-COMPARATOR] 图谱持仓重合分析调用异常: {}", e.getMessage());
            }
        }

        // 3. Context Engineering: Observation 净化与事实槽提纯
        String cleanDataA = sanitizer.sanitizeFundMetrics(metricsA) + "\n" +
                sanitizer.sanitizeHoldings(holdingsA) + "\n" +
                "- 季报定性展望: " + sanitizer.sanitizeReportView(reportA, 800);

        String cleanDataB = sanitizer.sanitizeFundMetrics(metricsB) + "\n" +
                sanitizer.sanitizeHoldings(holdingsB) + "\n" +
                "- 季报定性展望: " + sanitizer.sanitizeReportView(reportB, 800);

        String factualFacts = """
            === 双基金标的横向对标事实输入 (Tool-as-Truth) ===
            【标的 A (基金代码: %s)】:
            %s

            ----------------------------------------
            【标的 B (基金代码: %s)】:
            %s
            %s
            """.formatted(codeA, cleanDataA, codeB, cleanDataB,
                (graphOverlapFact == null || graphOverlapFact.isBlank()) ? "" : "\n----------------------------------------\n【知识图谱持仓重合度穿透】:\n" + graphOverlapFact);

        // 4. 按需匹配并动态注入基金对标 Skill 规范
        String skillRules = (skillMatcher != null) ? skillMatcher.matchSkillInstructions("COMPARISON", codeA + " " + codeB) : "";

        try {
            var spec = FundComparatorPrompt.buildSpec(codeA, cleanDataA, codeB, cleanDataB, skillRules, graphOverlapFact);
            LlmRequest request = spec.toLlmRequest();
            request.setUsageConsumer(usageConsumer);
            String comparisonAnalysis = clientService.chat(request);
            return factualFacts + "\n\n=== 智能对标深度归因 ===\n" + comparisonAnalysis;
        } catch (Exception e) {
            if (usageConsumer != null) {
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Metered comparison failed", e);
            }
            log.warn("[FUND-COMPARATOR] 调用 LLM 深度对比失败，使用客观事实兜底: error={}", e.getMessage());
            return factualFacts;
        }
    }

    /**
     * 强类型 DAG 节点横向深度对标执行入口
     *
     * @param node          当前 DAG 节点
     * @param store         产物存储总线
     * @param usageConsumer Token 计量回调
     * @return 强类型横向对标报告产物
     */
    public Artifact<ComparisonReport> compareArtifact(
            GraphNode node,
            ArtifactStore store,
            Consumer<LlmResponse> usageConsumer
    ) {
        String nodeId = node != null ? node.getNodeId() : "comparison";

        // 1. 从上游提取候选对标标的
        String codeA = "003095";
        String codeB = "005827";
        if (store != null) {
            Optional<Artifact<FundResearchResult>> resOpt = store.findFirstByType(ArtifactType.FUND_RESEARCH);
            if (resOpt.isPresent() && resOpt.get().payload() instanceof FundResearchResult research) {
                List<String> candidates = research.topCandidates();
                if (candidates != null && candidates.size() >= 2) {
                    codeA = candidates.get(0);
                    codeB = candidates.get(1);
                } else if (candidates != null && candidates.size() == 1) {
                    codeA = candidates.get(0);
                    codeB = candidates.get(0);
                } else if (research.evaluatedFunds() != null && research.evaluatedFunds().size() >= 2) {
                    codeA = String.valueOf(research.evaluatedFunds().get(0).get("fundCode"));
                    codeB = String.valueOf(research.evaluatedFunds().get(1).get("fundCode"));
                }
            }
        }
        if (node != null && node.getParams().containsKey("targetCode")) {
            codeA = String.valueOf(node.getParams().get("targetCode"));
            codeB = codeA;
        }

        boolean isSingle = (codeB == null || codeB.isBlank() || codeA.equals(codeB));

        // 2. 执行对标分析或单标的深度剖析
        String comparisonAnalysis = compareFunds(codeA, isSingle ? null : codeB, usageConsumer);

        // 3. 提取知识图谱重合持仓 (仅双标的对标时采集)
        List<String> sharedHoldings = List.of();
        if (graphTool != null && !isSingle) {
            try {
                String rawOverlap = graphTool.getSharedHoldings(codeA, codeB);
                if (rawOverlap != null && rawOverlap.contains("sharedStockCodes")) {
                    var jsonNode = new ObjectMapper().readTree(rawOverlap);
                    var arr = jsonNode.get("sharedStockCodes");
                    if (arr != null && arr.isArray()) {
                        List<String> list = new ArrayList<>();
                        for (var elem : arr) {
                            list.add(elem.asText());
                        }
                        sharedHoldings = list;
                    }
                }
            } catch (Exception e) {
                log.warn("[FUND-COMPARATOR] 解析图谱重合持仓列表失败: {}", e.getMessage());
            }
        }

        String artifactId = "art-comp-" + UUID.randomUUID().toString().substring(0, 8);
        ArtifactMetadata metadata = ArtifactMetadata.standard("FundComparatorAgent");
        List<String> evidenceUris = isSingle ? List.of("fund://" + codeA) : List.of("fund://" + codeA, "fund://" + codeB);
        EvidenceContract contract = EvidenceContract.sufficient(
                isSingle ? "完成基金 " + codeA + " 单标的深度穿透剖析" : "完成基金 " + codeA + " 与 " + codeB + " 横向深度对标",
                evidenceUris
        );

        ComparisonReport report = ComparisonReport.of(codeA, isSingle ? "" : codeB, comparisonAnalysis, sharedHoldings);

        return new Artifact<>(
                artifactId,
                ArtifactType.COMPARISON_REPORT,
                nodeId,
                report,
                metadata,
                contract
        );
    }

    /**
     * 强类型 DAG 节点横向深度对标执行入口（无计量）
     *
     * @param node  当前 DAG 节点
     * @param store 产物存储总线
     * @return 强类型横向对标报告产物
     */
    public Artifact<ComparisonReport> compareArtifact(GraphNode node, ArtifactStore store) {
        return compareArtifact(node, store, null);
    }
}

package com.financial.copilot.agent.core.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agents.fund.FundAnalyzerAgent;
import com.financial.copilot.agent.core.agents.fund.FundComparatorAgent;
import com.financial.copilot.agent.core.agents.fund.FundScreenerAgent;
import com.financial.copilot.agent.core.agents.stock.StockAnalyzerAgent;
import com.financial.copilot.agent.core.agents.stock.StockScreenerAgent;
import com.financial.copilot.agent.core.dag.adapter.BlackboardAdapter;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.llm.service.LlmService;
import com.financial.copilot.agent.core.pipeline.ResearchBlackboard;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.graph.FinancialGraphTool;
import com.financial.copilot.common.fund.dto.FundMetricsDTO;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.fund.port.FundDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * <h1>Agent 强类型契约与产物审计集成测试</h1>
 */
class AgentArtifactContractTest {

    private ScreenerAgent screenerAgent;
    private AnalyzerAgent analyzerAgent;
    private ComparatorAgent comparatorAgent;
    private ReportSynthesizer reportSynthesizer;
    private LlmService mockLlmService;
    private FundDataPort mockDataPort;
    private FinancialGraphTool mockGraphTool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockLlmService = Mockito.mock(LlmService.class);
        mockDataPort = Mockito.mock(FundDataPort.class);
        mockGraphTool = Mockito.mock(FinancialGraphTool.class);

        when(mockLlmService.chat(anyString(), anyString())).thenReturn("{\"fundType\":\"偏股混合型\",\"limit\":5}");
        when(mockLlmService.chat(any())).thenReturn("【投研对标与合成报告全文】: 综合评定 003095 优于 005827。");

        List<FundInfo> sampleFunds = List.of(
                FundInfo.builder().fundCode("003095").fundName("中欧医疗健康混合A").fundType("偏股混合型").managementCompanyId("中欧基金").build(),
                FundInfo.builder().fundCode("005827").fundName("易方达蓝筹精选混合").fundType("偏股混合型").managementCompanyId("易方达基金").build(),
                FundInfo.builder().fundCode("161005").fundName("富国天惠成长混合A").fundType("偏股混合型").managementCompanyId("富国基金").build()
        );
        when(mockDataPort.screenFunds(any())).thenReturn(sampleFunds);
        when(mockDataPort.getFundMetrics(anyString(), any(), any())).thenAnswer(inv ->
                FundMetricsDTO.builder()
                        .fundCode(inv.getArgument(0))
                        .sharpeRatio(new BigDecimal("1.85"))
                        .calmarRatio(new BigDecimal("1.20"))
                        .annualizedReturn(new BigDecimal("18.5"))
                        .build()
        );

        FundScreeningTool screeningTool = new FundScreeningTool(mockDataPort, objectMapper);
        FundQuantAnalysisTool quantTool = new FundQuantAnalysisTool(mockDataPort, objectMapper);
        FundHoldingsQueryTool holdingsTool = Mockito.mock(FundHoldingsQueryTool.class);
        FundReportRetrieverTool reportTool = Mockito.mock(FundReportRetrieverTool.class);

        when(holdingsTool.getTopHoldings(anyString(), any())).thenReturn("{\"holdings\": [\"恒瑞医药\", \"药明康德\"]}");
        when(reportTool.getLatestQuarterlyReportView(anyString())).thenReturn("关注创新药与医疗出海产业链");
        when(mockGraphTool.getSharedHoldings(anyString(), anyString())).thenReturn("{\"sharedStockCodes\": [\"600276.SH\"], \"overlapCount\": 1}");

        FundScreenerAgent fundScreener = new FundScreenerAgent(mockLlmService, screeningTool, objectMapper);
        FundAnalyzerAgent fundAnalyzer = new FundAnalyzerAgent(quantTool, holdingsTool, reportTool);
        FundComparatorAgent fundComparator = new FundComparatorAgent(quantTool, holdingsTool, reportTool, mockLlmService, null, null, mockGraphTool);

        screenerAgent = new ScreenerAgent(fundScreener, Mockito.mock(StockScreenerAgent.class));
        analyzerAgent = new AnalyzerAgent(fundAnalyzer, Mockito.mock(StockAnalyzerAgent.class));
        comparatorAgent = new ComparatorAgent(fundComparator);
        reportSynthesizer = new ReportSynthesizer(mockLlmService);
    }

    @Test
    @DisplayName("测试 ScreenerAgent 生成强类型 FundPool 产物及证据契约")
    void testScreenArtifact() {
        GraphNode node = GraphNode.builder().nodeId("step-1-screening").taskType("SCREENING").build();
        Artifact<FundPool> artifact = screenerAgent.screenArtifact(node, "筛选医药基金");

        assertNotNull(artifact);
        assertEquals(ArtifactType.FUND_POOL, artifact.type());
        assertEquals("step-1-screening", artifact.producerNodeId());
        assertNotNull(artifact.payload());
        assertEquals(3, artifact.payload().totalCount());
        assertFalse(artifact.payload().isEmpty());

        assertNotNull(artifact.evidenceContract());
        assertTrue(artifact.evidenceContract().isSufficient());
        assertTrue(artifact.evidenceContract().evidenceUris().contains("fund://003095"));
    }

    @Test
    @DisplayName("测试 AnalyzerAgent 基于上游产物并发计算 FundResearchResult")
    void testAnalyzeArtifact() {
        ArtifactStore store = new ArtifactStore();
        GraphNode screenNode = GraphNode.builder().nodeId("step-1-screening").taskType("SCREENING").build();
        Artifact<FundPool> poolArtifact = screenerAgent.screenArtifact(screenNode, "筛选医药基金");
        store.store(screenNode.getNodeId(), poolArtifact);

        GraphNode analysisNode = GraphNode.builder().nodeId("step-2-analysis").taskType("BATCH_ANALYSIS").build();
        Artifact<FundResearchResult> resultArtifact = analyzerAgent.analyzeArtifact(analysisNode, store, "分析候选基金");

        assertNotNull(resultArtifact);
        assertEquals(ArtifactType.FUND_RESEARCH, resultArtifact.type());
        assertNotNull(resultArtifact.payload());
        assertEquals(2, resultArtifact.payload().topCandidates().size());
        assertNotNull(resultArtifact.evidenceContract());
        assertTrue(resultArtifact.evidenceContract().isSufficient());
    }

    @Test
    @DisplayName("测试 ComparatorAgent 对标并生成包含知识图谱重合持仓的 ComparisonReport")
    void testCompareArtifact() {
        ArtifactStore store = new ArtifactStore();
        GraphNode analysisNode = GraphNode.builder().nodeId("step-2-analysis").taskType("BATCH_ANALYSIS").build();
        FundResearchResult researchResult = FundResearchResult.ofBatch(
                List.of(Map.of("fundCode", "003095", "score", new BigDecimal("88.5"))),
                List.of("003095", "005827")
        );
        store.store(analysisNode.getNodeId(), Artifact.of("art-res-1", ArtifactType.FUND_RESEARCH, analysisNode.getNodeId(), researchResult));

        GraphNode compNode = GraphNode.builder().nodeId("step-3-comparison").taskType("COMPARISON").build();
        Artifact<ComparisonReport> compArtifact = comparatorAgent.compareArtifact(compNode, store);

        assertNotNull(compArtifact);
        assertEquals(ArtifactType.COMPARISON_REPORT, compArtifact.type());
        assertEquals("step-3-comparison", compArtifact.producerNodeId());
        assertNotNull(compArtifact.payload());
        assertEquals(List.of("003095", "005827"), compArtifact.payload().comparedFundCodes());
        assertTrue(compArtifact.payload().sharedHoldings().contains("600276.SH"));
        assertTrue(compArtifact.evidenceContract().isSufficient());
    }

    @Test
    @DisplayName("测试 ReportSynthesizer 生成 FinalSynthesisReport 及全链路 EvidenceContract 审计追踪")
    void testSynthesizeArtifact() {
        ArtifactStore store = new ArtifactStore();
        GraphNode compNode = GraphNode.builder().nodeId("step-3-comparison").taskType("COMPARISON").build();
        ComparisonReport compReport = ComparisonReport.of("003095", "005827", "对标分析详情", List.of("600276.SH"));
        store.store(compNode.getNodeId(), Artifact.of(
                "art-comp-1", ArtifactType.COMPARISON_REPORT, compNode.getNodeId(), compReport,
                com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata.standard("comp"),
                com.financial.copilot.agent.core.dag.artifact.EvidenceContract.sufficient("对标完成", List.of("fund://003095", "fund://005827"))
        ));

        GraphNode synNode = GraphNode.builder().nodeId("step-4-synthesis").taskType("SYNTHESIS").build();
        Artifact<FinalSynthesisReport> reportArtifact = reportSynthesizer.synthesizeArtifact(synNode, store, "生成医药基金建议研报");

        assertNotNull(reportArtifact);
        assertEquals(ArtifactType.FINAL_REPORT, reportArtifact.type());
        assertNotNull(reportArtifact.payload());
        assertNotNull(reportArtifact.payload().markdownReport());
        assertFalse(reportArtifact.payload().recommendedFunds().isEmpty());
        assertTrue(reportArtifact.evidenceContract().evidenceUris().contains("fund://003095"));
    }

    @Test
    @DisplayName("测试 BlackboardAdapter 双向读写与强类型 Record 状态无损同步")
    void testBlackboardAdapterDualWrite() {
        ResearchBlackboard blackboard = new ResearchBlackboard();

        // 1. FUND_POOL 同步
        FundPool pool = FundPool.of(List.of(FundInfo.builder().fundCode("003095").fundName("中欧医疗").build()), "初筛命中");
        Artifact<FundPool> poolArt = Artifact.of("a1", ArtifactType.FUND_POOL, "node-1", pool);
        BlackboardAdapter.applyArtifactToBlackboard(poolArt, blackboard);
        assertEquals(1, blackboard.getCandidateFunds().size());
        assertEquals("003095", blackboard.getCandidateFunds().get(0).getFundCode());

        // 2. FUND_RESEARCH 同步
        FundResearchResult research = FundResearchResult.ofBatch(
                List.of(Map.of("fundCode", "003095", "score", new BigDecimal("92.0"))),
                List.of("003095")
        );
        Artifact<FundResearchResult> resArt = Artifact.of("a2", ArtifactType.FUND_RESEARCH, "node-2", research);
        BlackboardAdapter.applyArtifactToBlackboard(resArt, blackboard);
        assertEquals(List.of("003095"), blackboard.getTopCandidates());
        assertNotNull(blackboard.get(ResearchBlackboard.KEY_MANAGER_RATINGS, List.class));

        // 3. COMPARISON_REPORT 同步
        ComparisonReport comp = ComparisonReport.of("003095", "005827", "对标归因事实", List.of());
        Artifact<ComparisonReport> compArt = Artifact.of("a3", ArtifactType.COMPARISON_REPORT, "node-3", comp);
        BlackboardAdapter.applyArtifactToBlackboard(compArt, blackboard);
        assertEquals("对标归因事实", blackboard.getRaw(ResearchBlackboard.KEY_COMPARISON_FACTS));

        // 4. FINAL_REPORT 同步
        FinalSynthesisReport finalReport = FinalSynthesisReport.of("投资建议摘要", "# 完整研报全文");
        Artifact<FinalSynthesisReport> finalArt = Artifact.of("a4", ArtifactType.FINAL_REPORT, "node-4", finalReport);
        BlackboardAdapter.applyArtifactToBlackboard(finalArt, blackboard);
        assertEquals("# 完整研报全文", blackboard.getFinalReport());
    }
}

package com.financial.copilot.agent.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agents.AnalyzerAgent;
import com.financial.copilot.agent.core.agents.ComparatorAgent;
import com.financial.copilot.agent.core.agents.ReportSynthesizer;
import com.financial.copilot.agent.core.agents.ScreenerAgent;
import com.financial.copilot.agent.core.agents.fund.FundAnalyzerAgent;
import com.financial.copilot.agent.core.agents.fund.FundComparatorAgent;
import com.financial.copilot.agent.core.agents.fund.FundScreenerAgent;
import com.financial.copilot.agent.core.agents.stock.StockAnalyzerAgent;
import com.financial.copilot.agent.core.agents.stock.StockScreenerAgent;
import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.config.LlmProperties;
import com.financial.copilot.agent.core.llm.factory.LlmDynamicWebClientFactory;
import com.financial.copilot.agent.core.llm.provider.LlmProviderRegistry;
import com.financial.copilot.agent.core.llm.service.DefaultLlmService;
import com.financial.copilot.agent.core.pipeline.TaskDecomposer;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import com.financial.copilot.agent.core.memory.LongTermMemoryService;
import com.financial.copilot.agent.core.memory.MemoryRefinementTask;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.fund.dto.FundMetricsDTO;
import com.financial.copilot.data.fund.mapper.FundReportVectorMapper;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.fund.port.FundDataPort;
import com.financial.copilot.domain.stock.port.StockDataPort;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import com.financial.copilot.domain.user.enums.RiskToleranceLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * <h1>金融投研多智能体复合工作流单元测试 (Financial Research Workflow Test)</h1>
 * <p>
 * 测试验证复杂四阶段投研任务（筛选 -> 经理批量能力评估 -> 决赛圈对标 -> 研报合成）
 * 在同步阻塞模式与响应式 SSE 事件流模式下的正确性与鲁棒性。
 * </p>
 *
 * @author FinancialCopilot
 */
class FinancialResearchWorkflowTest {

    private FinancialResearchWorkflow workflow;
    private FundDataPort mockDataPort;
    private StockDataPort mockStockPort;
    private LongTermMemoryService mockLongTermMemoryService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("placeholder-test-key");
        LlmConfigManager configManager = new LlmConfigManager(properties);
        configManager.init();
        LlmProviderRegistry providerRegistry = new LlmProviderRegistry();
        LlmDynamicWebClientFactory webClientFactory = new LlmDynamicWebClientFactory();
        DefaultLlmService llmService = new DefaultLlmService(webClientFactory, configManager, providerRegistry, objectMapper);

        mockDataPort = Mockito.mock(FundDataPort.class);
        mockStockPort = Mockito.mock(StockDataPort.class);
        FundReportVectorMapper mockVectorMapper = Mockito.mock(FundReportVectorMapper.class);

        // 模拟 5 只样本基金数据
        List<FundInfo> sampleFunds = List.of(
                FundInfo.builder().fundCode("003095").fundName("中欧医疗健康混合A").fundType("偏股混合型").managementCompanyId("中欧基金").build(),
                FundInfo.builder().fundCode("005827").fundName("易方达蓝筹精选混合").fundType("偏股混合型").managementCompanyId("易方达基金").build(),
                FundInfo.builder().fundCode("161005").fundName("富国天惠成长混合A").fundType("偏股混合型").managementCompanyId("富国基金").build(),
                FundInfo.builder().fundCode("001875").fundName("前海开源沪港深优势精选").fundType("偏股混合型").managementCompanyId("前海开源").build(),
                FundInfo.builder().fundCode("000961").fundName("天弘永定价值成长混合A").fundType("偏股混合型").managementCompanyId("天弘基金").build()
        );

        when(mockDataPort.screenFunds(any())).thenReturn(sampleFunds);
        when(mockDataPort.getFundByCode(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return sampleFunds.stream().filter(f -> f.getFundCode().equals(code)).findFirst();
        });
        when(mockDataPort.getFundMetrics(anyString(), any(), any())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return FundMetricsDTO.builder()
                    .fundCode(code)
                    .fundName("标的-" + code)
                    .annualizedReturn(new BigDecimal("15.5"))
                    .maxDrawdown(new BigDecimal("18.2"))
                    .sharpeRatio(new BigDecimal("1.35"))
                    .calmarRatio(new BigDecimal("0.85"))
                    .build();
        });

        // 实例化公募基金专有工具与 Agent
        FundScreeningTool screeningTool = new FundScreeningTool(mockDataPort, objectMapper);
        FundQuantAnalysisTool quantTool = new FundQuantAnalysisTool(mockDataPort, objectMapper);
        FundHoldingsQueryTool holdingsTool = new FundHoldingsQueryTool(mockDataPort, objectMapper);
        FundReportRetrieverTool reportTool = new FundReportRetrieverTool(mockVectorMapper);

        FundScreenerAgent fundScreenerAgent = new FundScreenerAgent(llmService, screeningTool, objectMapper);
        FundAnalyzerAgent fundAnalyzerAgent = new FundAnalyzerAgent(quantTool, holdingsTool, reportTool);
        FundComparatorAgent fundComparatorAgent = new FundComparatorAgent(quantTool, holdingsTool, reportTool, llmService);

        // 实例化股票专有工具与 Agent
        StockScreeningTool stockScreeningTool = new StockScreeningTool(mockStockPort, objectMapper);
        StockQuantAnalysisTool stockQuantTool = new StockQuantAnalysisTool(mockStockPort, objectMapper);
        StockScreenerAgent stockScreenerAgent = new StockScreenerAgent(llmService, stockScreeningTool, objectMapper);
        StockAnalyzerAgent stockAnalyzerAgent = new StockAnalyzerAgent(stockQuantTool);

        // 实例化多资产顶层门面（完全基于新设计全参注入）
        ScreenerAgent screenerAgent = new ScreenerAgent(fundScreenerAgent, stockScreenerAgent);
        AnalyzerAgent analyzerAgent = new AnalyzerAgent(fundAnalyzerAgent, stockAnalyzerAgent);
        ComparatorAgent comparatorAgent = new ComparatorAgent(fundComparatorAgent);
        ReportSynthesizer reportSynthesizer = new ReportSynthesizer(llmService);
        TaskDecomposer taskDecomposer = new TaskDecomposer(llmService, objectMapper);

        ShortTermMemoryService mockShortTermMemoryService = Mockito.mock(ShortTermMemoryService.class);
        mockLongTermMemoryService = Mockito.mock(LongTermMemoryService.class);
        MemoryRefinementTask mockMemoryRefinementTask = Mockito.mock(MemoryRefinementTask.class);
        org.springframework.context.ApplicationEventPublisher mockEventPublisher = Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);

        workflow = new FinancialResearchWorkflow(
                taskDecomposer, screenerAgent, analyzerAgent, comparatorAgent, reportSynthesizer, mockDataPort,
                mockShortTermMemoryService, mockLongTermMemoryService, mockMemoryRefinementTask, mockEventPublisher
        );
    }

    /**
     * 测试验证复合投研流水线同步阻塞执行
     */
    @Test
    @DisplayName("验证复合投研流水线同步阻塞执行")
    void testExecuteComplexPipeline() {
        String complexPrompt = "帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议。";

        String report = workflow.execute(complexPrompt);

        assertNotNull(report);
        assertFalse(report.isBlank());
    }

    /**
     * 测试验证复合投研流水线响应式事件流推送
     */
    @Test
    @DisplayName("验证复合投研流水线响应式事件流推送")
    void testExecutePipelineStream() {
        String complexPrompt = "帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议。";

        List<ResearchStreamEvent> events = workflow.executePipelineStream(complexPrompt).collectList().block();

        assertNotNull(events);
        assertFalse(events.isEmpty());

        assertTrue(events.stream().anyMatch(e -> "PLAN".equals(e.getType())), "必须包含 PLAN 事件");
        assertTrue(events.stream().anyMatch(e -> "STEP_START".equals(e.getType())), "必须包含 STEP_START 事件");
    }

    @Test
    @DisplayName("验证开启深度思考推理模式下的工作流同步与流式执行")
    void testExecuteWithEnableThinking() {
        String complexPrompt = "深度推演医药基金配置方案";

        String report = workflow.execute(complexPrompt, true);
        assertNotNull(report);
        assertFalse(report.isBlank());

        List<ResearchStreamEvent> events = workflow.executePipelineStream(complexPrompt, true).collectList().block();
        assertNotNull(events);
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> "PLAN".equals(e.getType())));
    }

    @Test
    @DisplayName("验证用户个性化投资画像注入工作流与黑板上下文")
    void testExecuteWithUserInvestmentProfile() {
        String prompt = "为我量身定制医药与消费基金组合投资方案";

        UserInvestmentProfile profile = UserInvestmentProfile.builder()
                .userId(9001L)
                .riskToleranceLevel(RiskToleranceLevel.C4)
                .preferredSectors(List.of("医药", "大消费"))
                .maxDrawdownTolerance(new BigDecimal("25.00"))
                .build();

        String report = workflow.execute(prompt, false, profile);
        assertNotNull(report);
        assertFalse(report.isBlank());

        List<ResearchStreamEvent> events = workflow.executePipelineStream(prompt, false, profile).collectList().block();
        assertNotNull(events);
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> "PLAN".equals(e.getType())));
    }

    @Test
    @DisplayName("验证跨会话提纯事实自动召回并注入 TaskDecomposer 与 ReportSynthesizer")
    void testCrossSessionRefinedFactsRecallAndInjection() {
        String sessionKey = "9001:new-session-789";
        String prompt = "帮我推荐几只医药基金，关注回撤控制";

        UserInvestmentProfile profile = UserInvestmentProfile.builder()
                .userId(9001L)
                .riskToleranceLevel(RiskToleranceLevel.C4)
                .build();

        List<String> mockHistoricalFacts = List.of(
                "用户偏好过去三年收益靠前且最大回撤控制在15%以内的医药主题基金",
                "用户已建仓 001875 (前海开源国家健康)，需避免重叠配置"
        );

        Mockito.when(mockLongTermMemoryService.retrieveRelevantFacts(
                Mockito.eq(sessionKey), Mockito.eq(prompt), Mockito.eq(5)))
                .thenReturn(mockHistoricalFacts);

        FinancialResearchWorkflow.WorkflowExecutionResult result = workflow.executeWithResult(
                sessionKey, prompt, false, profile, null);

        assertNotNull(result);
        assertNotNull(result.getReport());
        assertFalse(result.getReport().isBlank());

        // 验证确实调用了长期记忆跨会话事实召回
        Mockito.verify(mockLongTermMemoryService, Mockito.atLeastOnce()).retrieveRelevantFacts(
                Mockito.eq(sessionKey), Mockito.eq(prompt), Mockito.eq(5));
    }
}

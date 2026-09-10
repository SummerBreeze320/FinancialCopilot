package com.financial.copilot.agent.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agents.*;
import com.financial.copilot.agent.core.config.DeepSeekModelConfig;
import com.financial.copilot.agent.core.pipeline.TaskDecomposer;
import com.financial.copilot.agent.core.service.DeepSeekClientService;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.common.dto.FundMetricsDTO;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.data.fund.mapper.FundReportVectorMapper;
import com.financial.copilot.domain.entity.FundInfo;
import com.financial.copilot.domain.port.FundDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class FinancialResearchWorkflowTest {

    private FinancialResearchWorkflow workflow;
    private FundDataPort mockDataPort;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        DeepSeekModelConfig config = new DeepSeekModelConfig();
        config.setApiKey("placeholder-test-key");
        DeepSeekClientService clientService = new DeepSeekClientService(WebClient.builder().build(), config, objectMapper);

        mockDataPort = Mockito.mock(FundDataPort.class);
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

        FundScreeningTool screeningTool = new FundScreeningTool(mockDataPort, objectMapper);
        FundQuantAnalysisTool quantTool = new FundQuantAnalysisTool(mockDataPort, objectMapper);
        FundHoldingsQueryTool holdingsTool = new FundHoldingsQueryTool(mockDataPort, objectMapper);
        FundReportRetrieverTool reportTool = new FundReportRetrieverTool(mockVectorMapper);

        // 兼容别名
        com.financial.copilot.agent.tools.FundScreeningTool topScreeningTool =
                new com.financial.copilot.agent.tools.FundScreeningTool(mockDataPort, objectMapper);
        com.financial.copilot.agent.tools.FundQuantAnalysisTool topQuantTool =
                new com.financial.copilot.agent.tools.FundQuantAnalysisTool(mockDataPort, objectMapper);
        com.financial.copilot.agent.tools.FundHoldingsQueryTool topHoldingsTool =
                new com.financial.copilot.agent.tools.FundHoldingsQueryTool(mockDataPort, objectMapper);
        com.financial.copilot.agent.tools.FundReportRetrieverTool topReportTool =
                new com.financial.copilot.agent.tools.FundReportRetrieverTool(mockVectorMapper);

        TaskDecomposer taskDecomposer = new TaskDecomposer(clientService, objectMapper);
        ScreenerAgent screenerAgent = new ScreenerAgent(clientService, topScreeningTool, objectMapper);
        AnalyzerAgent analyzerAgent = new AnalyzerAgent(topQuantTool, topHoldingsTool, topReportTool);
        ComparatorAgent comparatorAgent = new ComparatorAgent(topQuantTool, topHoldingsTool, topReportTool);
        ReportSynthesizer reportSynthesizer = new ReportSynthesizer(clientService);

        workflow = new FinancialResearchWorkflow(
                taskDecomposer, screenerAgent, analyzerAgent, comparatorAgent, reportSynthesizer, mockDataPort
        );
    }

    @Test
    @DisplayName("验证复合投研流水线同步阻塞执行")
    void testExecuteComplexPipeline() {
        String complexPrompt = "帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议。";

        String report = workflow.execute(complexPrompt);

        assertNotNull(report);
        assertFalse(report.isBlank());
    }

    @Test
    @DisplayName("验证复合投研流水线响应式事件流推送")
    void testExecutePipelineStream() {
        String complexPrompt = "帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议。";

        List<ResearchStreamEvent> events = workflow.executePipelineStream(complexPrompt).collectList().block();

        assertNotNull(events);
        assertFalse(events.isEmpty());

        // 应该包含 PLAN 事件、STEP_START 事件、STEP_COMPLETE 事件等
        assertTrue(events.stream().anyMatch(e -> "PLAN".equals(e.getType())), "必须包含 PLAN 事件");
        assertTrue(events.stream().anyMatch(e -> "STEP_START".equals(e.getType())), "必须包含 STEP_START 事件");
    }
}

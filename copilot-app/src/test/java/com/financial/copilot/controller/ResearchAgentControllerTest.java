package com.financial.copilot.controller;

import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.result.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * <h1>投研 Web 控制器单元测试 (Research Agent Controller Test)</h1>
 * <p>
 * 测试验证 {@link ResearchAgentController} 对健康检查接口、阶段式 SSE 流式推送接口以及同步生成接口的响应规范。
 * 完全基于新设计端点，无任何遗留兼容接口测试。
 * </p>
 *
 * @author FinancialCopilot
 */
class ResearchAgentControllerTest {

    private ResearchAgentController controller;
    private FinancialResearchWorkflow mockWorkflow;

    @BeforeEach
    void setUp() {
        mockWorkflow = Mockito.mock(FinancialResearchWorkflow.class);
        controller = new ResearchAgentController(mockWorkflow);
    }

    /**
     * 测试验证健康检查端点元数据与能力清单
     */
    @Test
    @DisplayName("验证健康检查端点元数据与能力清单")
    void testHealthCheck() {
        ApiResult<Map<String, Object>> result = controller.healthCheck();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        Map<String, Object> data = result.getData();
        assertEquals("UP", data.get("status"));
        assertEquals("FUND (公募基金深度实施)", data.get("activeDomain"));
        assertEquals("Lombok + MyBatis-Plus 3.5.7 + PGVector", data.get("orm"));
    }

    /**
     * 测试验证阶段式流式投研端点正常调用工作流
     */
    @Test
    @DisplayName("验证阶段式流式投研端点正常调用工作流")
    void testStreamPipelineChat() {
        String prompt = "帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议。";

        when(mockWorkflow.executePipelineStream(anyString(), any()))
                .thenReturn(Flux.just(
                        ResearchStreamEvent.plan(4, "测试规划"),
                        ResearchStreamEvent.stepStart(1, 4, "SCREENING", "初筛中"),
                        ResearchStreamEvent.done()
                ));

        List<ResearchStreamEvent> events = controller.streamPipelineChat(prompt, "HIGH").collectList().block();

        assertNotNull(events);
        assertEquals(3, events.size());
        assertEquals("PLAN", events.get(0).getType());
    }

    /**
     * 测试验证同步投研分析生成端点
     */
    @Test
    @DisplayName("验证同步研报生成端点响应")
    void testSyncChat() {
        ResearchAgentController.ChatRequest req = new ResearchAgentController.ChatRequest();
        req.setPrompt("分析中欧医疗健康混合A");
        req.setResearchDepth("MIDDLE");

        when(mockWorkflow.execute(anyString(), any())).thenReturn("# 投研分析报告");

        ApiResult<String> result = controller.syncChat(req);
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("# 投研分析报告", result.getData());
    }
}

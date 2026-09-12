package com.financial.copilot.controller;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import com.financial.copilot.agent.core.memory.LongTermMemoryService;
import com.financial.copilot.agent.core.memory.RefinedFact;
import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
    private WalletBillingService mockBillingService;
    private UserService mockUserService;
    private ShortTermMemoryService mockShortTermMemoryService;
    private LongTermMemoryService mockLongTermMemoryService;
    private final Authentication auth = new UsernamePasswordAuthenticationToken(
            UserPrincipal.builder().userId(1L).build(), null, List.of());

    @BeforeEach
    void setUp() {
        mockWorkflow = Mockito.mock(FinancialResearchWorkflow.class);
        mockBillingService = Mockito.mock(WalletBillingService.class);
        mockUserService = Mockito.mock(UserService.class);
        mockShortTermMemoryService = Mockito.mock(ShortTermMemoryService.class);
        mockLongTermMemoryService = Mockito.mock(LongTermMemoryService.class);
        controller = new ResearchAgentController(mockWorkflow, mockBillingService, mockUserService,
                mockShortTermMemoryService, mockLongTermMemoryService);
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

        when(mockWorkflow.executePipelineStream(any(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(Flux.just(
                        ResearchStreamEvent.plan(4, "测试规划"),
                        ResearchStreamEvent.stepStart(1, 4, "SCREENING", "初筛中"),
                        ResearchStreamEvent.done()
                ));

        List<ResearchStreamEvent> events = controller.streamPipelineChat(prompt, "test-session", true, 1L)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).collectList().block();

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
        req.setSessionId("sess-001");
        req.setEnableThinking(true);

        when(mockWorkflow.executeWithResult(any(), anyString(), anyBoolean(), any(), any())).thenReturn(FinancialResearchWorkflow.WorkflowExecutionResult.builder().report("# 投研分析报告").build());

        ApiResult<String> result = controller.syncChat(req)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("# 投研分析报告", result.getData());
    }

    /**
     * 测试验证结构化工作流执行端点
     */
    @Test
    @DisplayName("验证结构化工作流触发执行端点")
    void testExecuteWorkflow() {
        ResearchAgentController.WorkflowExecuteRequest req = new ResearchAgentController.WorkflowExecuteRequest();
        req.setPrompt("全维度评测易方达蓝筹精选混合");
        req.setSessionId("sess-workflow-123");
        req.setEnableThinking(false);

        FinancialResearchWorkflow.WorkflowExecutionResult executionResult =
                FinancialResearchWorkflow.WorkflowExecutionResult.builder()
                        .sessionId("sess-workflow-123")
                        .report("# 易方达蓝筹精选深度分析报告")
                        .durationMs(1250L)
                        .build();

        when(mockWorkflow.executeWithResult(any(), anyString(), anyBoolean(), any(), any())).thenReturn(executionResult);

        ApiResult<ResearchAgentController.WorkflowExecuteResponse> apiResult = controller.executeWorkflow(req)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        assertNotNull(apiResult);
        assertEquals(200, apiResult.getCode());

        ResearchAgentController.WorkflowExecuteResponse data = apiResult.getData();
        assertNotNull(data);
        assertEquals("sess-workflow-123", data.getSessionId());
        assertEquals("# 易方达蓝筹精选深度分析报告", data.getReport());
        assertNull(data.getModel()); // No provider usage was reported by this workflow double.
        assertEquals(1250L, data.getExecutionTimeMs());
    }

    /**
     * 测试验证会话记忆与提纯事实查询端点
     */
    @Test
    @DisplayName("验证会话记忆与提纯事实查询端点")
    void testGetSessionMemory() {
        String sessionId = "sess-mem-test";
        String key = SecurityUtils.sessionKey(1L, sessionId);
        when(mockShortTermMemoryService.getContext(key)).thenReturn(List.of("User: 选基要求", "Bot: 筛选完毕"));
        when(mockLongTermMemoryService.retrieve(key, 20)).thenReturn(List.of("Final report generated: ..."));

        RefinedFact fact = new RefinedFact(sessionId, "FACT", "用户风险承受偏好为进取型");
        when(mockLongTermMemoryService.getRefinedFacts(key)).thenReturn(List.of(fact));

        ApiResult<ResearchAgentController.SessionMemoryResponse> apiResult = controller.getSessionMemory(sessionId).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        assertNotNull(apiResult);
        assertEquals(200, apiResult.getCode());

        ResearchAgentController.SessionMemoryResponse mem = apiResult.getData();
        assertNotNull(mem);
        assertEquals(sessionId, mem.getSessionId());
        assertEquals(2, mem.getShortTermMessages().size());
        assertEquals(1, mem.getLongTermEntries().size());
        assertEquals(1, mem.getRefinedFacts().size());
        assertEquals("用户风险承受偏好为进取型", mem.getRefinedFacts().get(0).getContent());
    }

    /**
     * 测试验证算力余额不足异常捕获处理 (HTTP 402)
     */
    @Test
    @DisplayName("验证算力余额不足异常捕获处理")
    void testWalletInsufficientExceptionHandling() {
        WalletInsufficientException ex = new WalletInsufficientException(1L, 30L, 100L);

        ApiResult<Map<String, Object>> result = controller.handleWalletInsufficient(ex);
        assertNotNull(result);
        assertEquals(402, result.getCode());
        assertEquals(1L, result.getData().get("userId"));
        assertEquals(30L, result.getData().get("currentBalance"));
        assertEquals(100L, result.getData().get("requiredPoints"));
    }

    @Test
    void sameSessionIdCannotReadAnotherUsersMemory() {
        String session = "shared-client-id";
        String ownerKey = SecurityUtils.sessionKey(1L, session);
        when(mockShortTermMemoryService.getContext(ownerKey)).thenReturn(List.of("private"));
        var other = new UsernamePasswordAuthenticationToken(UserPrincipal.builder().userId(2L).build(), null, List.of());
        var result = controller.getSessionMemory(session)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(other)).block();
        assertTrue(result.getData().getShortTermMessages().isEmpty());
        Mockito.verify(mockShortTermMemoryService, Mockito.never()).getContext(ownerKey);
    }

    @Test
    void workflowUsageIsChargedToAuthenticatedOwnerWithActualModelAndTokens() {
        var request = new ResearchAgentController.WorkflowExecuteRequest();
        request.setPrompt("test");
        request.setSessionId("usage-session");
        when(mockWorkflow.executeWithResult(any(), anyString(), anyBoolean(), any(), any())).thenAnswer(inv -> {
            Consumer<LlmResponse> consumer = inv.getArgument(4);
            consumer.accept(LlmResponse.builder()
                    .provider(LlmProviderType.DEEPSEEK)
                    .model("actual-model").promptTokens(13).completionTokens(7).totalTokens(20).latencyMs(9L).build());
            return FinancialResearchWorkflow.WorkflowExecutionResult.builder().report("ok").build();
        });
        var result = controller.executeWorkflow(request)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        assertEquals("actual-model", result.getData().getModel());
        Mockito.verify(mockBillingService).deductTokenPoints(1L,
                SecurityUtils.sessionKey(1L, "usage-session"),
                "LLM_CALL", "DEEPSEEK", "actual-model", 13, 7, 9L);
    }
}

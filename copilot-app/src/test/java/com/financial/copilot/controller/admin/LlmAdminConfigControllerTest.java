package com.financial.copilot.controller.admin;

import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.config.LlmProperties;
import com.financial.copilot.agent.core.llm.dto.LlmConnectionTestRequest;
import com.financial.copilot.agent.core.llm.dto.LlmConnectionTestResult;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.core.llm.provider.LlmProviderMetadata;
import com.financial.copilot.agent.core.llm.provider.LlmProviderRegistry;
import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import com.financial.copilot.agent.core.llm.service.LlmService;
import com.financial.copilot.common.result.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <h1>大模型研发测试与后台管理控制器单元测试 (LLM Admin Config Controller Test)</h1>
 * <p>
 * 验证厂商元数据清单查询、全局活动配置读取与动态更新、连通性探测接口的正确性。
 * </p>
 *
 * @author FinancialCopilot
 */
class LlmAdminConfigControllerTest {

    private LlmAdminConfigController controller;
    private LlmProviderRegistry registry;
    private LlmConfigManager configManager;
    private LlmService mockLlmService;

    @BeforeEach
    void setUp() {
        registry = new LlmProviderRegistry();

        LlmProperties properties = new LlmProperties();
        properties.setDefaultProvider(LlmProviderType.DEEPSEEK);
        properties.setDefaultModel("deepseek-chat");
        properties.setDefaultReasoningModel("deepseek-reasoner");
        properties.setDefaultEnableThinking(false);
        properties.setBaseUrl("https://api.deepseek.com/v1");
        properties.setApiKey("test-key");

        configManager = new LlmConfigManager(properties);
        configManager.init();

        mockLlmService = Mockito.mock(LlmService.class);
        controller = new LlmAdminConfigController(registry, configManager, mockLlmService);
    }

    @Test
    @DisplayName("验证后台管理端列举所有支持的厂商与模型规格")
    void testListProviders() {
        ApiResult<List<LlmProviderMetadata>> result = controller.listProviders();
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertFalse(result.getData().isEmpty());
    }

    @Test
    @DisplayName("验证后台管理端读取与热更新系统当前生效大模型")
    void testGetAndUpdateActiveConfig() {
        // 1. 读取当前配置
        ApiResult<LlmSettingsDTO> currentResult = controller.getActiveConfig();
        assertNotNull(currentResult);
        assertEquals(200, currentResult.getCode());
        assertEquals(LlmProviderType.DEEPSEEK, currentResult.getData().getProvider());
        assertEquals("deepseek-chat", currentResult.getData().getModel());
        assertEquals("deepseek-reasoner", currentResult.getData().getReasoningModel());
        assertFalse(currentResult.getData().isEnableThinking());

        // 2. 模拟研发人员热更新为通义千问双模型
        LlmSettingsDTO updateReq = LlmSettingsDTO.builder()
                .provider(LlmProviderType.QWEN)
                .model("qwen-plus")
                .reasoningModel("qwq-32b")
                .enableThinking(true)
                .build();

        ApiResult<LlmSettingsDTO> updateResult = controller.updateActiveConfig(updateReq);
        assertNotNull(updateResult);
        assertEquals(200, updateResult.getCode());
        assertEquals(LlmProviderType.QWEN, updateResult.getData().getProvider());
        assertEquals("qwen-plus", updateResult.getData().getModel());
        assertEquals("qwq-32b", updateResult.getData().getReasoningModel());
        assertTrue(updateResult.getData().isEnableThinking());
    }

    @Test
    @DisplayName("验证后台管理端对大模型端点执行连通性即时探测")
    void testTestConnection() {
        LlmConnectionTestRequest req = LlmConnectionTestRequest.builder()
                .provider(LlmProviderType.DEEPSEEK)
                .model("deepseek-chat")
                .build();

        when(mockLlmService.testConnection(any(LlmConnectionTestRequest.class)))
                .thenReturn(LlmConnectionTestResult.builder()
                        .success(true)
                        .latencyMs(120L)
                        .sampleResponse("pong")
                        .build());

        ApiResult<LlmConnectionTestResult> result = controller.testConnection(req);
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertTrue(result.getData().isSuccess());
        assertEquals(120L, result.getData().getLatencyMs());
    }
}

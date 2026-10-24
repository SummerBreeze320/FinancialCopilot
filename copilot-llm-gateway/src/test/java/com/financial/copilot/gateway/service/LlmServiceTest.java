package com.financial.copilot.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.gateway.catalog.LlmModelOption;
import com.financial.copilot.gateway.catalog.LlmProviderMetadata;
import com.financial.copilot.gateway.catalog.LlmProviderRegistry;
import com.financial.copilot.gateway.catalog.LlmProviderType;
import com.financial.copilot.gateway.client.LlmGatewayClient;
import com.financial.copilot.gateway.config.LlmConfigManager;
import com.financial.copilot.gateway.config.LlmProperties;
import com.financial.copilot.gateway.model.LlmConnectionTestRequest;
import com.financial.copilot.gateway.model.LlmConnectionTestResult;
import com.financial.copilot.gateway.model.LlmGatewayRequest;
import com.financial.copilot.gateway.model.LlmGatewayResponse;
import com.financial.copilot.gateway.model.LlmRequest;
import com.financial.copilot.gateway.model.LlmSettingsDTO;
import com.financial.copilot.gateway.transport.LlmDynamicWebClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <h1>多厂商大模型统一服务门面测试 (LlmServiceTest)</h1>
 * <p>
 * 验证多厂商元数据注册表、标准模型与推理模型双模型路由机制、网关委托调用与网络降级兜底。
 * </p>
 *
 * @author FinancialCopilot
 */
class LlmServiceTest {

    private LlmProviderRegistry registry;
    private LlmConfigManager configManager;
    private DefaultLlmService llmService;
    private ObjectMapper objectMapper;
    private LlmGatewayClient mockGatewayClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new LlmProviderRegistry();

        LlmProperties properties = new LlmProperties();
        properties.setDefaultProvider(LlmProviderType.DEEPSEEK);
        properties.setDefaultModel("deepseek-chat");
        properties.setDefaultReasoningModel("deepseek-reasoner");
        properties.setDefaultEnableThinking(false);
        properties.setBaseUrl("https://api.deepseek.com/v1");
        properties.setApiKey("test-api-key");

        configManager = new LlmConfigManager(properties);
        configManager.init();

        LlmDynamicWebClientFactory clientFactory = new LlmDynamicWebClientFactory();
        mockGatewayClient = Mockito.mock(LlmGatewayClient.class);
        llmService = new DefaultLlmService(clientFactory, configManager, registry, objectMapper, mockGatewayClient);
    }

    @Test
    @DisplayName("验证多厂商注册表元数据与标准/推理双模型清单正确性")
    void testProviderRegistry() {
        List<LlmProviderMetadata> providers = registry.getAllProviders();
        assertNotNull(providers);
        assertFalse(providers.isEmpty());

        assertTrue(providers.stream().anyMatch(p -> p.providerType() == LlmProviderType.DEEPSEEK));
        assertTrue(providers.stream().anyMatch(p -> p.providerType() == LlmProviderType.OPENAI));
        assertTrue(providers.stream().anyMatch(p -> p.providerType() == LlmProviderType.QWEN));
        assertTrue(providers.stream().anyMatch(p -> p.providerType() == LlmProviderType.ZHIPU));
        assertTrue(providers.stream().anyMatch(p -> p.providerType() == LlmProviderType.OLLAMA));

        LlmProviderMetadata deepseek = registry.getMetadata(LlmProviderType.DEEPSEEK);
        assertEquals("deepseek-chat", deepseek.defaultModel());
        assertEquals("deepseek-reasoner", deepseek.defaultReasoningModel());
        assertTrue(deepseek.supportedModels().stream().anyMatch(LlmModelOption::supportsStreaming));

        LlmProviderMetadata openai = registry.getMetadata(LlmProviderType.OPENAI);
        assertEquals("gpt-4o", openai.defaultModel());
        assertEquals("o3-mini", openai.defaultReasoningModel());
    }

    @Test
    @DisplayName("验证平台管理员动态热更新 LLM 参数与双模型切换")
    void testDynamicConfigUpdate() {
        LlmSettingsDTO currentSettings = configManager.getActiveSettings();
        assertEquals(LlmProviderType.DEEPSEEK, currentSettings.getProvider());
        assertEquals("deepseek-chat", currentSettings.getModel());
        assertEquals("deepseek-reasoner", currentSettings.getReasoningModel());
        assertFalse(currentSettings.isEnableThinking());

        LlmSettingsDTO newSettings = LlmSettingsDTO.builder()
                .provider(LlmProviderType.QWEN)
                .model("qwen-max")
                .reasoningModel("qwq-32b")
                .enableThinking(true)
                .customBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .customApiKey("sk-qwen-test")
                .temperature(0.2)
                .maxTokens(8192)
                .build();

        configManager.updateActiveSettings(newSettings);

        LlmSettingsDTO updated = configManager.getActiveSettings();
        assertEquals(LlmProviderType.QWEN, updated.getProvider());
        assertEquals("qwen-max", updated.getModel());
        assertEquals("qwq-32b", updated.getReasoningModel());
        assertTrue(updated.isEnableThinking());
        assertEquals(0.2, updated.getTemperature());
        assertEquals(8192, updated.getMaxTokens());
    }

    @Test
    @DisplayName("验证双模型路由与真实 API 参数约束（推理模型自动禁用 temperature）")
    void testDualModelRoutingAndApiConstraints() {
        LlmSettingsDTO standard = LlmSettingsDTO.builder()
                .model("deepseek-chat")
                .reasoningModel("deepseek-reasoner")
                .enableThinking(false)
                .temperature(0.2)
                .build();
        assertEquals("deepseek-chat", standard.resolveEffectiveModel());
        assertEquals(0.2, standard.resolveTemperature());
        assertEquals(4096, standard.resolveMaxTokens());

        LlmSettingsDTO thinking = LlmSettingsDTO.builder()
                .model("deepseek-chat")
                .reasoningModel("deepseek-reasoner")
                .enableThinking(true)
                .temperature(0.2)
                .build();
        assertEquals("deepseek-reasoner", thinking.resolveEffectiveModel());
        assertNull(thinking.resolveTemperature(), "推理模型应当置空 temperature 防止 API 报 400 错误");
        assertEquals(8192, thinking.resolveMaxTokens());

        assertTrue(LlmSettingsDTO.isReasoningModel("o1-preview"));
        assertTrue(LlmSettingsDTO.isReasoningModel("o3-mini"));
        assertTrue(LlmSettingsDTO.isReasoningModel("deepseek-reasoner"));
        assertFalse(LlmSettingsDTO.isReasoningModel("gpt-4o"));
        assertFalse(LlmSettingsDTO.isReasoningModel("deepseek-chat"));
    }

    @Test
    @DisplayName("验证网关执行器委托调用成功")
    void testGatewayDelegationSuccess() {
        when(mockGatewayClient.executeSync(any(LlmGatewayRequest.class)))
                .thenReturn(LlmGatewayResponse.builder()
                        .content("网关真实响应结果")
                        .actualModel("deepseek-chat")
                        .build());

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("系统提示词")
                .userPrompt("用户问题")
                .build();

        String result = llmService.chat(request);
        assertEquals("网关真实响应结果", result);
    }

    @Test
    @DisplayName("验证无有效 API Key 时的智能 Mock 兜底响应")
    void testFallbackMockGeneration() {
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("你是一名资深分析师")
                .userPrompt("分析医药行业走势")
                .settings(LlmSettingsDTO.builder()
                        .customApiKey("")
                        .enableThinking(true)
                        .build())
                .build();

        String response = llmService.chat(request);
        assertNotNull(response);
        assertFalse(response.isBlank());
        assertTrue(response.contains("投研研报"));
    }

    @Test
    @DisplayName("验证测试连接接口在 Mock 模式下的成功响应")
    void testConnectionMockMode() {
        LlmConnectionTestRequest testRequest = LlmConnectionTestRequest.builder()
                .provider(LlmProviderType.DEEPSEEK)
                .apiKey("")
                .build();

        LlmConnectionTestResult result = llmService.testConnection(testRequest);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.getSampleResponse().contains("Pong"));
    }
}

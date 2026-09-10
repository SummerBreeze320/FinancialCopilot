package com.financial.copilot.agent.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.config.LlmProperties;
import com.financial.copilot.agent.core.llm.dto.*;
import com.financial.copilot.agent.core.llm.factory.LlmDynamicWebClientFactory;
import com.financial.copilot.agent.core.llm.provider.LlmModelOption;
import com.financial.copilot.agent.core.llm.provider.LlmProviderMetadata;
import com.financial.copilot.agent.core.llm.provider.LlmProviderRegistry;
import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import com.financial.copilot.agent.core.llm.service.DefaultLlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>多厂商大模型引擎与双模型路由核心服务单元测试 (LLM Service Test)</h1>
 * <p>
 * 测试验证多厂商元数据注册表、标准模型与推理模型双模型路由机制、动态热切换运行时配置及真实 API 参数约束。
 * </p>
 *
 * @author FinancialCopilot
 */
class LlmServiceTest {

    private LlmProviderRegistry registry;
    private LlmConfigManager configManager;
    private DefaultLlmService llmService;
    private ObjectMapper objectMapper;

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
        llmService = new DefaultLlmService(clientFactory, configManager, registry, objectMapper);
    }

    @Test
    @DisplayName("验证多厂商注册表元数据与标准/推理双模型清单正确性")
    void testProviderRegistry() {
        List<LlmProviderMetadata> providers = registry.getAllProviders();
        assertNotNull(providers);
        assertFalse(providers.isEmpty());

        // 验证常见主流厂商均已就绪
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

        // 模拟开发者在后台切换为 QWEN 通义千问
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
        // 1. 标准模式 -> deepseek-chat, 温度生效
        LlmSettingsDTO standard = LlmSettingsDTO.builder()
                .model("deepseek-chat")
                .reasoningModel("deepseek-reasoner")
                .enableThinking(false)
                .temperature(0.2)
                .build();
        assertEquals("deepseek-chat", standard.resolveEffectiveModel());
        assertEquals(0.2, standard.resolveTemperature());
        assertEquals(4096, standard.resolveMaxTokens());

        // 2. 深度思考推理模式 -> deepseek-reasoner, 官方禁止自定义温度，返回 null
        LlmSettingsDTO thinking = LlmSettingsDTO.builder()
                .model("deepseek-chat")
                .reasoningModel("deepseek-reasoner")
                .enableThinking(true)
                .temperature(0.2)
                .build();
        assertEquals("deepseek-reasoner", thinking.resolveEffectiveModel());
        assertNull(thinking.resolveTemperature(), "推理模型应当置空 temperature 防止 API 报 400 错误");
        assertEquals(8192, thinking.resolveMaxTokens());

        // 3. OpenAI o1/o3-mini 模型识别
        assertTrue(LlmSettingsDTO.isReasoningModel("o1-preview"));
        assertTrue(LlmSettingsDTO.isReasoningModel("o3-mini"));
        assertTrue(LlmSettingsDTO.isReasoningModel("deepseek-reasoner"));
        assertFalse(LlmSettingsDTO.isReasoningModel("gpt-4o"));
        assertFalse(LlmSettingsDTO.isReasoningModel("deepseek-chat"));
    }

    @Test
    @DisplayName("验证 LLM 无可用网络连接时的开发兜底响应")
    void testFallbackMockGeneration() {
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("你是一名资深分析师")
                .userPrompt("分析医药行业走势")
                .settings(LlmSettingsDTO.builder().enableThinking(true).build())
                .build();

        String response = llmService.chat(request);
        assertNotNull(response);
        assertFalse(response.isBlank());
    }
}

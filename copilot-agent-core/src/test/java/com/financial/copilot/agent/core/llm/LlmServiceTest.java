package com.financial.copilot.agent.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.config.LlmProperties;
import com.financial.copilot.agent.core.llm.dto.*;
import com.financial.copilot.agent.core.llm.factory.LlmDynamicWebClientFactory;
import com.financial.copilot.agent.core.llm.provider.LlmModelOption;
import com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel;
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
 * <h1>多厂商大模型引擎与动态配置核心服务单元测试 (LLM Service Test)</h1>
 * <p>
 * 测试验证多厂商元数据注册表、动态热切换运行时配置、性能档位映射及端到端调用兜底逻辑。
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
        properties.setBaseUrl("https://api.deepseek.com/v1");
        properties.setApiKey("test-api-key");
        properties.setDefaultPerformanceLevel(LlmPerformanceLevel.MIDDLE);

        configManager = new LlmConfigManager(properties);
        configManager.init();

        LlmDynamicWebClientFactory clientFactory = new LlmDynamicWebClientFactory();
        llmService = new DefaultLlmService(clientFactory, configManager, registry, objectMapper);
    }

    @Test
    @DisplayName("验证多厂商注册表元数据与默认模型清单正确性")
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
        assertTrue(deepseek.supportedModels().stream().anyMatch(LlmModelOption::supportsStreaming));
    }

    @Test
    @DisplayName("验证平台管理员动态热更新 LLM 参数")
    void testDynamicConfigUpdate() {
        LlmSettingsDTO currentSettings = configManager.getActiveSettings();
        assertEquals(LlmProviderType.DEEPSEEK, currentSettings.getProvider());
        assertEquals("deepseek-chat", currentSettings.getModel());

        // 模拟开发者在后台切换为 QWEN 通义千问
        LlmSettingsDTO newSettings = LlmSettingsDTO.builder()
                .provider(LlmProviderType.QWEN)
                .model("qwen-max")
                .customBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .customApiKey("sk-qwen-test")
                .temperature(0.2)
                .maxTokens(8192)
                .performanceLevel(LlmPerformanceLevel.HIGH)
                .build();

        configManager.updateActiveSettings(newSettings);

        LlmSettingsDTO updated = configManager.getActiveSettings();
        assertEquals(LlmProviderType.QWEN, updated.getProvider());
        assertEquals("qwen-max", updated.getModel());
        assertEquals(0.2, updated.getTemperature());
        assertEquals(8192, updated.getMaxTokens());
        assertEquals(LlmPerformanceLevel.HIGH, updated.getPerformanceLevel());
    }

    @Test
    @DisplayName("验证性能档位与思考强度转换逻辑")
    void testPerformanceLevelMapping() {
        assertEquals(LlmPerformanceLevel.LOW, LlmPerformanceLevel.fromString("low"));
        assertEquals(LlmPerformanceLevel.MIDDLE, LlmPerformanceLevel.fromString("MIDDLE"));
        assertEquals(LlmPerformanceLevel.HIGH, LlmPerformanceLevel.fromString("high"));
        // 容错默认值
        assertEquals(LlmPerformanceLevel.MIDDLE, LlmPerformanceLevel.fromString("unknown"));
        assertEquals(LlmPerformanceLevel.MIDDLE, LlmPerformanceLevel.fromString(null));
    }

    @Test
    @DisplayName("验证 LLM 无可用网络连接时的开发兜底响应")
    void testFallbackMockGeneration() {
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("你是一名资深分析师")
                .userPrompt("分析医药行业走势")
                .settings(LlmSettingsDTO.builder().performanceLevel(LlmPerformanceLevel.LOW).build())
                .build();

        String response = llmService.chat(request);
        assertNotNull(response);
        assertFalse(response.isBlank());
    }
}

package com.financial.copilot.gateway.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>LlmProviderRegistry 单元测试</h1>
 */
class LlmProviderRegistryTest {

    @Test
    @DisplayName("默认注册中心包含所有主流预置大模型厂商")
    void shouldContainAllPresetProviders() {
        LlmProviderRegistry registry = new LlmProviderRegistry();
        List<LlmProviderMetadata> providers = registry.getAllProviders();

        assertThat(providers).isNotEmpty();
        assertThat(providers).extracting(LlmProviderMetadata::providerType)
                .contains(LlmProviderType.DEEPSEEK, LlmProviderType.OPENAI, LlmProviderType.QWEN, LlmProviderType.ZHIPU, LlmProviderType.OLLAMA);
    }

    @Test
    @DisplayName("按枚举类型和字符串名称均能正确检索厂商元数据")
    void shouldFindProviderByTypeAndName() {
        LlmProviderRegistry registry = new LlmProviderRegistry();

        Optional<LlmProviderMetadata> byEnum = registry.getProvider(LlmProviderType.DEEPSEEK);
        assertThat(byEnum).isPresent();
        assertThat(byEnum.get().defaultModel()).isEqualTo("deepseek-chat");
        assertThat(byEnum.get().defaultReasoningModel()).isEqualTo("deepseek-reasoner");

        Optional<LlmProviderMetadata> byString = registry.getProvider("qwen");
        assertThat(byString).isPresent();
        assertThat(byString.get().providerType()).isEqualTo(LlmProviderType.QWEN);

        Optional<LlmProviderMetadata> nonExistent = registry.getProvider("non_existent");
        assertThat(nonExistent).isEmpty();
    }

    @Test
    @DisplayName("正确列出厂商支持的模型选项规格")
    void shouldListModelsForProvider() {
        LlmProviderRegistry registry = new LlmProviderRegistry();
        List<LlmModelOption> models = registry.getModelsForProvider(LlmProviderType.OPENAI);

        assertThat(models).isNotEmpty();
        assertThat(models).extracting(LlmModelOption::modelId)
                .contains("gpt-4o", "o3-mini");
    }
}

package com.financial.copilot.gateway.config;

import com.financial.copilot.gateway.catalog.LlmProviderType;
import com.financial.copilot.gateway.model.LlmSettingsDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>LlmConfigManager 单元测试</h1>
 */
class LlmConfigManagerTest {

    @Test
    @DisplayName("初始默认配置加载正确")
    void shouldLoadDefaultSettings() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider(LlmProviderType.DEEPSEEK);
        props.setDefaultModel("deepseek-chat");
        props.setDefaultReasoningModel("deepseek-reasoner");
        props.setBaseUrl("https://api.deepseek.com/v1");

        LlmConfigManager manager = new LlmConfigManager(props);
        manager.init();

        LlmSettingsDTO settings = manager.getActiveSettings();
        assertThat(settings).isNotNull();
        assertThat(settings.getProvider()).isEqualTo(LlmProviderType.DEEPSEEK);
        assertThat(settings.getModel()).isEqualTo("deepseek-chat");
        assertThat(settings.getReasoningModel()).isEqualTo("deepseek-reasoner");
        assertThat(settings.getCustomBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    @DisplayName("动态热更新活动配置成功")
    void shouldUpdateActiveSettings() {
        LlmProperties props = new LlmProperties();
        LlmConfigManager manager = new LlmConfigManager(props);
        manager.init();

        LlmSettingsDTO update = LlmSettingsDTO.builder()
                .provider(LlmProviderType.QWEN)
                .model("qwen-max")
                .reasoningModel("qwq-32b")
                .enableThinking(true)
                .customBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .customApiKey("sk-qwen-test")
                .build();

        LlmSettingsDTO updated = manager.updateActiveSettings(update);
        assertThat(updated.getProvider()).isEqualTo(LlmProviderType.QWEN);
        assertThat(updated.getModel()).isEqualTo("qwen-max");
        assertThat(updated.isEnableThinking()).isTrue();
        assertThat(manager.getActiveSettings().getModel()).isEqualTo("qwen-max");
    }
}

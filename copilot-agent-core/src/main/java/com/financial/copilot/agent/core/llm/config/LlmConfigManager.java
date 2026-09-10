package com.financial.copilot.agent.core.llm.config;

import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * <h1>全局动态大模型配置管理器 (LLM Runtime Configuration Manager)</h1>
 * <p>
 * 职责：维护当前系统运行时的活动大模型配置（包括标准模型与深度推理模型）。
 * 初始化自 {@link LlmProperties}，并支持研发测试/管理员通过后台接口动态热切换全局生效模型，具备线程安全保障。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class LlmConfigManager {

    private final LlmProperties properties;
    private final AtomicReference<LlmSettingsDTO> activeSettings = new AtomicReference<>();

    public LlmConfigManager(LlmProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        LlmSettingsDTO initial = LlmSettingsDTO.builder()
                .provider(properties.getDefaultProvider() != null ? properties.getDefaultProvider() : LlmProviderType.DEEPSEEK)
                .model(properties.getDefaultModel() != null ? properties.getDefaultModel() : "deepseek-chat")
                .reasoningModel(properties.getDefaultReasoningModel() != null ? properties.getDefaultReasoningModel() : "deepseek-reasoner")
                .enableThinking(properties.isDefaultEnableThinking())
                .customBaseUrl(properties.getBaseUrl())
                .customApiKey(properties.getApiKey())
                .build();
        activeSettings.set(initial);
        log.info("[LLM-CONFIG] 初始载入活动大模型配置: provider={}, model={}, reasoningModel={}, enableThinking={}, baseUrl={}",
                initial.getProvider(), initial.getModel(), initial.getReasoningModel(), initial.isEnableThinking(), initial.getCustomBaseUrl());
    }

    /**
     * 获取当前系统全局生效的大模型配置快照
     *
     * @return 当前生效的配置
     */
    public LlmSettingsDTO getActiveSettings() {
        return activeSettings.get();
    }

    /**
     * 动态热更新系统生效配置（研发测试或管理员操作）
     *
     * @param newSettings 新配置对象
     * @return 更新后的生效配置
     */
    public LlmSettingsDTO updateActiveSettings(LlmSettingsDTO newSettings) {
        if (newSettings == null) {
            return activeSettings.get();
        }

        LlmSettingsDTO current = activeSettings.get();
        LlmSettingsDTO merged = LlmSettingsDTO.builder()
                .provider(newSettings.getProvider() != null ? newSettings.getProvider() : current.getProvider())
                .model((newSettings.getModel() != null && !newSettings.getModel().isBlank()) ? newSettings.getModel() : current.getModel())
                .reasoningModel((newSettings.getReasoningModel() != null && !newSettings.getReasoningModel().isBlank())
                        ? newSettings.getReasoningModel() : current.getReasoningModel())
                .enableThinking(newSettings.isEnableThinking())
                .temperature(newSettings.getTemperature() != null ? newSettings.getTemperature() : current.getTemperature())
                .topP(newSettings.getTopP() != null ? newSettings.getTopP() : current.getTopP())
                .maxTokens(newSettings.getMaxTokens() != null ? newSettings.getMaxTokens() : current.getMaxTokens())
                .customBaseUrl((newSettings.getCustomBaseUrl() != null && !newSettings.getCustomBaseUrl().isBlank())
                        ? newSettings.getCustomBaseUrl() : current.getCustomBaseUrl())
                .customApiKey((newSettings.getCustomApiKey() != null && !newSettings.getCustomApiKey().isBlank())
                        ? newSettings.getCustomApiKey() : current.getCustomApiKey())
                .build();

        activeSettings.set(merged);
        log.info("[LLM-CONFIG] 系统大模型活动配置已热更新: provider={}, model={}, reasoningModel={}, enableThinking={}",
                merged.getProvider(), merged.getModel(), merged.getReasoningModel(), merged.isEnableThinking());
        return merged;
    }
}

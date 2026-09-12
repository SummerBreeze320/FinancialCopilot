package com.financial.copilot.agent.core.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.function.Consumer;

/**
 * <h1>统一大模型调用请求传输对象 (LLM Invocation Request)</h1>
 * <p>
 * 职责：封装发送给大模型的 System Prompt、User Prompt 以及可选的动态配置覆盖 {@link LlmSettingsDTO}。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest implements Serializable {

    /**
     * 系统提示词 (System Prompt)
     */
    private String systemPrompt;

    /**
     * 用户提示词或上下文信息 (User Prompt / Query)
     */
    private String userPrompt;

    /**
     * 动态模型与性能覆盖设置（若为 null 则使用全局默认设置）
     */
    private LlmSettingsDTO settings;

    /**
     * 服务端 Token 计量回调 (不参与序列化)
     */
    @JsonIgnore
    private transient Consumer<LlmResponse> usageConsumer;

    /**
     * 便捷构建基础请求
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 请求对象
     */
    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .build();
    }

    /**
     * 便捷构建带动态设置的请求
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param settings     配置覆盖
     * @return 请求对象
     */
    public static LlmRequest of(String systemPrompt, String userPrompt, LlmSettingsDTO settings) {
        return LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .settings(settings)
                .build();
    }
}

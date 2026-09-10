package com.financial.copilot.agent.core.llm.dto;

import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>统一大模型调用结果与审计度量传输对象 (LLM Invocation Response)</h1>
 * <p>
 * 职责：封装大模型生成的文本结果，并附带厂商、模型以及 Token 计量统计数据，为后续计费流水审计提供基础。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse implements Serializable {

    /**
     * 大模型返回的正文内容 (Markdown / JSON)
     */
    private String content;

    /**
     * 实际调用的厂商
     */
    private LlmProviderType provider;

    /**
     * 实际调用的模型名称
     */
    private String model;

    /**
     * 输入 Token 数
     */
    private Integer promptTokens;

    /**
     * 输出 Token 数
     */
    private Integer completionTokens;

    /**
     * 总 Token 数
     */
    private Integer totalTokens;

    /**
     * 推理耗时 (毫秒)
     */
    private Long latencyMs;
}

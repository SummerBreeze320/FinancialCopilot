package com.financial.copilot.agent.core.llm.dto;

import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>大模型厂商连通性测试请求传输对象 (LLM Connection Test Request)</h1>
 * <p>
 * 职责：开发测试人员在控制台点击“测试连接”时传入的探测载荷。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmConnectionTestRequest implements Serializable {

    /**
     * 待探测厂商
     */
    private LlmProviderType provider;

    /**
     * 待探测模型名称
     */
    private String model;

    /**
     * 探测 Base URL（可选，未填使用厂商默认）
     */
    private String baseUrl;

    /**
     * 待验证 API Key
     */
    private String apiKey;
}

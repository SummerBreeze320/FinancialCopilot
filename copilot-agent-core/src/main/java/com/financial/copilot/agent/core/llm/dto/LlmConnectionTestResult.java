package com.financial.copilot.agent.core.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>大模型厂商连通性测试结果传输对象 (LLM Connection Test Result)</h1>
 * <p>
 * 职责：返回厂商端点网络握手、鉴权与极简推理响应的状态，包含响应延迟与错误信息。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmConnectionTestResult implements Serializable {

    /**
     * 是否探测成功
     */
    private boolean success;

    /**
     * 状态说明信息或错误堆栈
     */
    private String message;

    /**
     * 网络与模型握手往返延迟 (毫秒)
     */
    private Long latencyMs;

    /**
     * 模型简要回复示例（如 "pong" 或测试响应）
     */
    private String sampleResponse;

    public static LlmConnectionTestResult success(long latencyMs, String sampleResponse) {
        return LlmConnectionTestResult.builder()
                .success(true)
                .message("连接成功！端点响应正常。")
                .latencyMs(latencyMs)
                .sampleResponse(sampleResponse)
                .build();
    }

    public static LlmConnectionTestResult failure(String errorMessage) {
        return LlmConnectionTestResult.builder()
                .success(false)
                .message("连接失败: " + errorMessage)
                .latencyMs(-1L)
                .build();
    }
}

package com.financial.copilot.agent.core.infra.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h1>MCP 工具调用结果封装</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpCallResult {

    /**
     * 工具执行返回的文本内容
     */
    private String content;

    /**
     * 是否执行失败
     */
    private boolean isError;

    /**
     * 错误信息（若有）
     */
    private String errorMessage;

    /**
     * 构建成功响应
     *
     * @param content 文本内容
     * @return 成功对象
     */
    public static McpCallResult success(String content) {
        return McpCallResult.builder()
                .content(content)
                .isError(false)
                .build();
    }

    /**
     * 构建失败响应
     *
     * @param errorMessage 错误描述
     * @return 失败对象
     */
    public static McpCallResult error(String errorMessage) {
        return McpCallResult.builder()
                .content(errorMessage)
                .isError(true)
                .errorMessage(errorMessage)
                .build();
    }
}

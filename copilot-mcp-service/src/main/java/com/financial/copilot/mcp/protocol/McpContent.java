package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h1>MCP 消息/工具调用返回内容片段对象</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpContent {

    @Builder.Default
    private String type = "text";

    /**
     * 内容文本（通常为序列化后的事实结果或 JSON 字符串）
     */
    private String text;

    public static McpContent text(String text) {
        return new McpContent("text", text);
    }
}

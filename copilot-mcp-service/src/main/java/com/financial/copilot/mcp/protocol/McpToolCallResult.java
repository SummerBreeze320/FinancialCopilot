package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>MCP tools/call 执行结果对象</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpToolCallResult {

    @Builder.Default
    private List<McpContent> content = new ArrayList<>();

    @Builder.Default
    private Boolean isError = false;

    public static McpToolCallResult success(String text) {
        return McpToolCallResult.builder()
                .content(List.of(McpContent.text(text)))
                .isError(false)
                .build();
    }

    public static McpToolCallResult error(String errorMsg) {
        return McpToolCallResult.builder()
                .content(List.of(McpContent.text(errorMsg)))
                .isError(true)
                .build();
    }
}

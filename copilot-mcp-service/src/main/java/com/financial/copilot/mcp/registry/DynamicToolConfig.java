package com.financial.copilot.mcp.registry;

import com.financial.copilot.mcp.protocol.McpToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>YAML 声明式外挂工具配置模型</h1>
 * <p>
 * 允许在 application.yml 中直接配置第三方 HTTP REST/Webhook 工具，
 * 无需编写任何 Java 代码即可动态向 MCP 暴露。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicToolConfig {

    /**
     * 工具标识名
     */
    private String name;

    /**
     * 工具功能说明
     */
    private String description;

    /**
     * 第三方 HTTP 请求 URL
     */
    private String endpoint;

    /**
     * HTTP 方法（GET 或 POST，默认 POST）
     */
    @Builder.Default
    private String method = "POST";

    /**
     * 自定义请求头
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /**
     * 超时时间（毫秒，默认 10000ms）
     */
    @Builder.Default
    private int timeoutMs = 10000;

    /**
     * 参数定义列表
     */
    @Builder.Default
    private List<ParameterConfig> parameters = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterConfig {
        private String name;
        @Builder.Default
        private String type = "string";
        private String description;
        private boolean required;
    }

    /**
     * 转换为符合 Draft-07 的 MCP JSON Schema 定义
     */
    public McpToolDefinition toDefinition() {
        Map<String, Object> properties = new HashMap<>();
        List<String> requiredList = new ArrayList<>();

        if (parameters != null) {
            for (ParameterConfig param : parameters) {
                Map<String, Object> prop = new HashMap<>();
                prop.put("type", param.getType() != null ? param.getType() : "string");
                if (param.getDescription() != null) {
                    prop.put("description", param.getDescription());
                }
                properties.put(param.getName(), prop);

                if (param.isRequired()) {
                    requiredList.add(param.getName());
                }
            }
        }

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", requiredList);

        return McpToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
    }
}

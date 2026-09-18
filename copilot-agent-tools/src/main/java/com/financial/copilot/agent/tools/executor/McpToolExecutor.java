package com.financial.copilot.agent.tools.executor;

import com.financial.copilot.agent.tools.client.WindMcpClient;
import com.financial.copilot.agent.tools.distiller.ComponentDataDistiller;
import com.financial.copilot.agent.tools.model.*;
import com.financial.copilot.agent.tools.workspace.ConfiguredToolWorkspaceBuilder;
import com.financial.copilot.agent.tools.workspace.ToolWorkspacePayload;
import com.financial.copilot.agent.tools.workspace.ToolWorkspaceReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * <h1>MCP JSON-RPC 协议执行器</h1>
 * <p>
 * 负责与 Wind MCP Server (如 wind-fund-analysis, wind-fund-holdings) 交互，
 * 触发如 fund_get_similar, fund_get_brinson_attribution 等底层接口，
 * 并对产出的组件树与结构化结论执行方案 A 内存蒸馏。
 * </p>
 */
@Slf4j
@Component
public class McpToolExecutor implements ToolExecutor {

    private final ComponentDataDistiller distiller;
    private final ConfiguredToolWorkspaceBuilder workspaceBuilder;
    private final WindMcpClient mcpClient;

    @Autowired
    public McpToolExecutor(ComponentDataDistiller distiller,
                           ConfiguredToolWorkspaceBuilder workspaceBuilder,
                           @Autowired(required = false) WindMcpClient mcpClient) {
        this.distiller = distiller;
        this.workspaceBuilder = workspaceBuilder;
        this.mcpClient = mcpClient;
    }

    public McpToolExecutor(ComponentDataDistiller distiller, ConfiguredToolWorkspaceBuilder workspaceBuilder) {
        this(distiller, workspaceBuilder, null);
    }

    @Override
    public boolean supports(ToolDefinition definition, ToolExecuteRequest request) {
        if (request.getSourceMode() != ToolSourceMode.EXTERNAL_CONFIGURED) {
            return false;
        }
        return "mcp".equalsIgnoreCase(definition.getProvider());
    }

    @Override
    public ToolExecuteResult execute(ToolDefinition definition, ToolExecuteRequest request) {
        log.info("Executing MCP tool: {} [server={}, tool={}]",
                definition.getId(), definition.getServer(), definition.getTargetTool());
        try {
            Map<String, Object> rawData = new HashMap<>();
            rawData.put("toolId", definition.getId());
            rawData.put("server", definition.getServer());
            rawData.put("mcpTool", definition.getTargetTool());
            rawData.put("arguments", request.getArguments());

            String mcpSummary = null;
            String mcpBusinessContent = null;
            if (mcpClient != null) {
                String sessionId = request.getSessionId();
                String server = definition.getServer();
                String targetTool = definition.getTargetTool() != null ? definition.getTargetTool() : definition.getId();
                Map<String, Object> mcpCallResult = mcpClient.call(sessionId, server, targetTool, request.getArguments());
                if (mcpCallResult != null) {
                    rawData.put("mcpResult", mcpCallResult);
                    Object summaryObj = mcpCallResult.get("summary");
                    if (summaryObj instanceof String s && !s.isBlank()) {
                        mcpSummary = s;
                    }
                    Object contentObj = mcpCallResult.get("content");
                    if (contentObj instanceof String s && !s.isBlank()) {
                        mcpBusinessContent = s;
                    }
                }
            }

            List<UITreeComponent> components = definition.getComponents() != null ? definition.getComponents() : List.of();
            StringBuilder llmTextBuilder = new StringBuilder();
            llmTextBuilder.append("【").append(definition.getName()).append(" MCP 分析结论】:\n");

            // 优先引入 MCP 远端结构化提炼的 Markdown 或 Summary 结论
            if (mcpSummary != null) {
                llmTextBuilder.append("【核心摘要】: ").append(mcpSummary).append("\n\n");
            }
            if (mcpBusinessContent != null) {
                llmTextBuilder.append(mcpBusinessContent).append("\n\n");
            }

            for (UITreeComponent comp : components) {
                try {
                    String distilled = distiller.distill(comp);
                    llmTextBuilder.append(distilled).append("\n\n");
                } catch (Exception ex) {
                    log.warn("Distillation failed for component: {}", comp.getId(), ex);
                    String compName = comp.getName() != null ? comp.getName() : comp.getId();
                    llmTextBuilder.append("【组件 ").append(compName).append(" 数据暂不可用】\n\n");
                }
            }

            UITreeComponent primary = components.isEmpty() ? null : components.getFirst();
            ToolWorkspacePayload workspace = workspaceBuilder.build(definition, request, components);
            List<ToolWorkspaceReference> references = workspaceBuilder.references(workspace);

            return ToolExecuteResult.builder()
                    .success(true)
                    .textForLlm(llmTextBuilder.toString().trim())
                    .visualComponent(primary)
                    .visualComponents(components)
                    .workspacePayload(workspace)
                    .references(references)
                    .rawData(rawData)
                    .build();
        } catch (Exception e) {
            log.error("McpToolExecutor failed for tool: {}", definition.getId(), e);
            return ToolExecuteResult.ofFailure("MCP 调用异常: " + e.getMessage());
        }
    }
}

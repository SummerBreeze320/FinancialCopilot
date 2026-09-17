package com.financial.copilot.agent.tools.executor;

import com.financial.copilot.agent.tools.distiller.ComponentDataDistiller;
import com.financial.copilot.agent.tools.configured.model.*;
import com.financial.copilot.agent.tools.model.*;
import com.financial.copilot.agent.tools.workspace.ConfiguredToolWorkspaceBuilder;
import com.financial.copilot.agent.tools.workspace.ToolWorkspacePayload;
import com.financial.copilot.agent.tools.workspace.ToolWorkspaceReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP JSON-RPC 协议执行器。
 * 负责与 Wind MCP Server (如 wind-fund-analysis) 交互，
 * 触发如 fund_get_similar, fund_get_brinson_attribution 等底层接口，
 * 并对产出的一组组件执行方案 A 内存蒸馏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolExecutor implements ToolExecutor {

    private final ComponentDataDistiller distiller;
    private final ConfiguredToolWorkspaceBuilder workspaceBuilder;

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

            List<UITreeComponent> components = definition.getComponents() != null ? definition.getComponents() : List.of();
            StringBuilder llmTextBuilder = new StringBuilder();
            llmTextBuilder.append("【").append(definition.getName()).append(" MCP 分析结论】:\n");

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

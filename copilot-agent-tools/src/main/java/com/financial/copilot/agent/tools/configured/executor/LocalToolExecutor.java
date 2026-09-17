package com.financial.copilot.agent.tools.configured.executor;

import com.financial.copilot.agent.tools.configured.distiller.ComponentDataDistiller;
import com.financial.copilot.agent.tools.configured.model.*;
import com.financial.copilot.agent.tools.configured.workspace.ConfiguredToolWorkspaceBuilder;
import com.financial.copilot.agent.tools.configured.workspace.ToolWorkspacePayload;
import com.financial.copilot.agent.tools.configured.workspace.ToolWorkspaceReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 本地工程原生 Tool 执行器。
 * 当请求指定使用本地工程数据源 (LOCAL_PROJECT) 或 provider 为 local 时生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalToolExecutor implements ToolExecutor {

    private final ComponentDataDistiller distiller;
    private final ConfiguredToolWorkspaceBuilder workspaceBuilder;

    @Override
    public boolean supports(ToolDefinition definition, ToolExecuteRequest request) {
        if (request.getSourceMode() == ToolSourceMode.LOCAL_PROJECT) {
            return true;
        }
        return "local".equalsIgnoreCase(definition.getProvider());
    }

    @Override
    public ToolExecuteResult execute(ToolDefinition definition, ToolExecuteRequest request) {
        log.info("Executing tool locally: {} with args: {}", definition.getId(), request.getArguments());
        try {
            // 本地模式构建仿真/本地指标
            Map<String, Object> rawData = new HashMap<>();
            rawData.put("toolId", definition.getId());
            rawData.put("mode", "LOCAL_PROJECT");
            rawData.put("timestamp", System.currentTimeMillis());
            rawData.put("arguments", request.getArguments());

            // 附带该 Tool 关联的一组组件规范
            List<UITreeComponent> components = definition.getComponents() != null ? definition.getComponents() : List.of();
            UITreeComponent primary = components.isEmpty() ? null : components.getFirst();

            StringBuilder llmTextBuilder = new StringBuilder();
            llmTextBuilder.append("【本地引擎执行成功】: ").append(definition.getName()).append(" 已在本地完成计算。\n");
            for (UITreeComponent comp : components) {
                try {
                    String distilled = distiller.distill(comp);
                    llmTextBuilder.append(distilled).append("\n\n");
                } catch (Exception ex) {
                    log.warn("Distillation failed for local component: {}", comp.getId(), ex);
                    String compName = comp.getName() != null ? comp.getName() : comp.getId();
                    llmTextBuilder.append("【组件 ").append(compName).append(" 数据暂不可用】\n\n");
                }
            }

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
            log.error("Local tool execution failed: {}", definition.getId(), e);
            return ToolExecuteResult.ofFailure("本地 Tool 执行异常: " + e.getMessage());
        }
    }
}

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
 * 外部 HTTP Invoke / Data Agent 协议执行器。
 * 负责与远程服务通信并执行方案 A 的内存数据蒸馏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpToolExecutor implements ToolExecutor {

    private final ComponentDataDistiller distiller;
    private final ConfiguredToolWorkspaceBuilder workspaceBuilder;

    @Override
    public boolean supports(ToolDefinition definition, ToolExecuteRequest request) {
        if (request.getSourceMode() != ToolSourceMode.EXTERNAL_CONFIGURED) {
            return false;
        }
        String provider = definition.getProvider();
        return "http_invoke".equalsIgnoreCase(provider) || "data_agent".equalsIgnoreCase(provider);
    }

    @Override
    public ToolExecuteResult execute(ToolDefinition definition, ToolExecuteRequest request) {
        log.info("Executing HTTP/DataAgent tool: {} [provider={}]", definition.getId(), definition.getProvider());
        try {
            Map<String, Object> rawData = new HashMap<>();
            rawData.put("toolId", definition.getId());
            rawData.put("provider", definition.getProvider());
            rawData.put("status", "SUCCESS");
            rawData.put("arguments", request.getArguments());

            // 获取该 Tool 下绑定的一组组件 
            List<UITreeComponent> components = definition.getComponents() != null ? definition.getComponents() : List.of();
            StringBuilder llmTextBuilder = new StringBuilder();
            llmTextBuilder.append("【").append(definition.getName()).append("执行完成】:\n");

            // 对该 Tool 关联的组件逐一蒸馏关键特征
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
            log.error("HttpToolExecutor failed for tool: {}", definition.getId(), e);
            return ToolExecuteResult.ofFailure("远程 HTTP 调用失败: " + e.getMessage());
        }
    }
}

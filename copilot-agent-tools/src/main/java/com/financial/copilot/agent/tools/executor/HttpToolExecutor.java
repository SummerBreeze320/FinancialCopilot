package com.financial.copilot.agent.tools.executor;

import com.financial.copilot.agent.tools.client.FundInfraWebClient;
import com.financial.copilot.agent.tools.distiller.ComponentDataDistiller;
import com.financial.copilot.agent.tools.model.*;
import com.financial.copilot.agent.tools.workspace.ConfiguredToolWorkspaceBuilder;
import com.financial.copilot.agent.tools.workspace.ToolWorkspacePayload;
import com.financial.copilot.agent.tools.workspace.ToolWorkspaceReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * <h1>外部 HTTP Invoke / Data Agent 协议执行器</h1>
 * <p>
 * 负责与 Wind FundInfraWeb 基础数据网关通信并执行方案 A 的内存数据蒸馏。
 * </p>
 */
@Slf4j
@Component
public class HttpToolExecutor implements ToolExecutor {

    private final ComponentDataDistiller distiller;
    private final ConfiguredToolWorkspaceBuilder workspaceBuilder;
    private final FundInfraWebClient infraWebClient;

    @Autowired
    public HttpToolExecutor(ComponentDataDistiller distiller,
                            ConfiguredToolWorkspaceBuilder workspaceBuilder,
                            @Autowired(required = false) FundInfraWebClient infraWebClient) {
        this.distiller = distiller;
        this.workspaceBuilder = workspaceBuilder;
        this.infraWebClient = infraWebClient;
    }

    public HttpToolExecutor(ComponentDataDistiller distiller, ConfiguredToolWorkspaceBuilder workspaceBuilder) {
        this(distiller, workspaceBuilder, null);
    }

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

            // 若配置了 FundInfraWeb 客户端，则并发/按需针对各组件的 command 发起真实数据拉取
            Map<String, Object> componentDataMap = new HashMap<>();
            if (infraWebClient != null && definition.getComponents() != null) {
                String sessionId = request.getSessionId();
                for (UITreeComponent comp : definition.getComponents()) {
                    if (StringUtils.hasText(comp.getCommand())) {
                        Map<String, Object> compParams = resolveComponentParams(comp, request.getArguments());
                        Object invokeResult = infraWebClient.invokeValue(comp.getCommand(), compParams, sessionId);
                        if (invokeResult != null) {
                            componentDataMap.put(comp.getId(), invokeResult);
                        }
                    }
                }
            }
            if (!componentDataMap.isEmpty()) {
                rawData.put("componentData", componentDataMap);
            }

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

    private Map<String, Object> resolveComponentParams(UITreeComponent comp, Map<String, Object> requestArgs) {
        Map<String, Object> params = new HashMap<>();
        if (requestArgs != null) {
            params.putAll(requestArgs);
        }
        if (comp.getParams() != null) {
            for (String paramName : comp.getParams()) {
                if (paramName.contains(":")) {
                    String[] parts = paramName.split(":", 2);
                    String key = parts[0].trim();
                    String defVal = parts[1].trim();
                    if (!params.containsKey(key)) {
                        params.put(key, defVal);
                    }
                }
            }
        }
        return params;
    }
}

package com.financial.copilot.agent.tools.configured.executor;

import com.financial.copilot.agent.tools.configured.distiller.ComponentDataDistiller;
import com.financial.copilot.agent.tools.configured.model.*;
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

            String textForLlm = "【本地引擎执行成功】: " + definition.getName() + " 已在本地完成计算。";
            if (primary != null) {
                textForLlm += "\n" + distiller.distill(primary);
            }

            return ToolExecuteResult.builder()
                    .success(true)
                    .textForLlm(textForLlm)
                    .visualComponent(primary)
                    .visualComponents(components)
                    .rawData(rawData)
                    .build();
        } catch (Exception e) {
            log.error("Local tool execution failed: {}", definition.getId(), e);
            return ToolExecuteResult.ofFailure("本地 Tool 执行异常: " + e.getMessage());
        }
    }
}

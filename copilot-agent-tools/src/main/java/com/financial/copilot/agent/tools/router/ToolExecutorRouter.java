package com.financial.copilot.agent.tools.router;

import com.financial.copilot.agent.tools.executor.ToolExecutor;
import com.financial.copilot.agent.tools.model.ToolDefinition;
import com.financial.copilot.agent.tools.model.ToolExecuteRequest;
import com.financial.copilot.agent.tools.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一 Tool 执行路由中枢 (ToolExecutorRouter)。
 * 根据 Tool 注册元数据与调用模式，智能匹配最合适的 ToolExecutor 执行调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutorRouter {

    private final ToolRegistry registry;
    private final List<ToolExecutor> executors;

    /**
     * 执行 Tool 请求。
     *
     * @param request 执行请求对象
     * @return 双轨执行结果
     */
    public ToolExecuteResult routeAndExecute(ToolExecuteRequest request) {
        String toolId = request.getToolId();
        ToolDefinition definition = registry.get(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Unrecognized tool identifier: " + toolId));

        for (ToolExecutor executor : executors) {
            if (executor.supports(definition, request)) {
                log.debug("Routing tool {} to executor {}", toolId, executor.getClass().getSimpleName());
                return executor.execute(definition, request);
            }
        }

        throw new IllegalStateException("No suitable ToolExecutor found for tool: " + toolId
                + ", provider: " + definition.getProvider() + ", sourceMode: " + request.getSourceMode());
    }
}

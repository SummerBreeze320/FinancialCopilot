package com.financial.copilot.agent.tools.configured.executor;

import com.financial.copilot.agent.tools.configured.model.ToolDefinition;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteRequest;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;

/**
 * 统一 Tool 执行器接口。
 */
public interface ToolExecutor {

    /**
     * 判断当前执行器是否支持处理该工具和请求。
     */
    boolean supports(ToolDefinition definition, ToolExecuteRequest request);

    /**
     * 执行工具调用并返回双轨结果。
     */
    ToolExecuteResult execute(ToolDefinition definition, ToolExecuteRequest request);
}

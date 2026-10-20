package com.financial.copilot.agent.core.infra.agentscope;

import com.financial.copilot.agent.core.infra.agentscope.loop.AgentTerminationStatus;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import java.util.List;
import java.util.Map;

/**
 * <h1>AgentScope 调用执行结果载体 (AgentScopeInvocation)</h1>
 * <p>封装单次 ReAct Agent 执行产出的最终消息、工具观测详情以及终止保护降级元数据。</p>
 *
 * @param reply 模型最终产出的回复消息
 * @param observations 节点执行生命周期中捕获的工具执行结果字典
 * @param degraded 是否触发了防死循环或熔断安全降级
 * @param terminationStatus 结构化终止状态（如 TASK_COMPLETED, NO_AVAILABLE_ACTION, MAX_ITERATIONS_REACHED 等）
 * @author FinancialCopilot
 */
public record AgentScopeInvocation(
        Msg reply,
        Map<String, List<ToolResultBlock>> observations,
        boolean degraded,
        AgentTerminationStatus terminationStatus
) {

    public AgentScopeInvocation(Msg reply, Map<String, List<ToolResultBlock>> observations) {
        this(reply, observations, false, AgentTerminationStatus.TASK_COMPLETED);
    }

    public AgentScopeInvocation(Msg reply, Map<String, List<ToolResultBlock>> observations, boolean degraded, String reason) {
        this(reply, observations, degraded, resolveStatus(reason, degraded));
    }

    /**
     * 兼容返回终止原因字符串名称
     *
     * @return 终止原因枚举名称
     */
    public String terminationReason() {
        return terminationStatus != null ? terminationStatus.name() : null;
    }

    private static AgentTerminationStatus resolveStatus(String reason, boolean degraded) {
        if (reason == null) {
            return degraded ? AgentTerminationStatus.NO_AVAILABLE_ACTION : AgentTerminationStatus.TASK_COMPLETED;
        }
        try {
            return AgentTerminationStatus.valueOf(reason);
        } catch (IllegalArgumentException e) {
            return degraded ? AgentTerminationStatus.NO_AVAILABLE_ACTION : AgentTerminationStatus.TASK_COMPLETED;
        }
    }

    /**
     * 校验并提取指定工具最后一次调用的文本输出
     *
     * @param toolName 工具名称
     * @return 工具文本结果
     */
    public String requireLastText(String toolName) {
        List<ToolResultBlock> results = observations.get(toolName);
        if (results == null || results.isEmpty()) throw new IllegalStateException("Agent did not call required tool: " + toolName);
        ToolResultBlock result = results.get(results.size() - 1);
        if (result.getState() != ToolResultState.SUCCESS) throw new IllegalStateException("Tool failed: " + toolName);
        String text = result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", (left, right) -> left + right);
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            return json.isTextual() ? json.asText() : text;
        } catch (Exception ignored) {
            return text;
        }
    }
}

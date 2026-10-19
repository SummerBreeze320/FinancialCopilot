package com.financial.copilot.agent.core.business.agents.a2a;

import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import io.a2a.spec.AgentCard;

import java.util.List;
import java.util.Locale;

/**
 * <h1>A2A (Agent-to-Agent) 可执行智能体标准契约</h1>
 * <p>
 * 遵循开源 A2A 协议规范，每个智能体持有自描述的 {@link AgentCard}，
 * 支持基于 TaskType 与 AssetCategory 的能力自省与动态匹配。
 * 具体智能体运行时统一由 {@link AgentScopeAgentFactory} 驱动。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface A2aExecutableAgent {

    /**
     * 获取智能体的标准 A2A 名片元数据
     *
     * @return 符合 io.a2a.spec.AgentCard 规范的实例
     */
    AgentCard agentCard();

    /**
     * 执行当前 DAG 节点分析任务
     *
     * @param node 当前 DAG 节点元数据
     * @param input 输入产物集合
     * @param context 节点运行时上下文
     * @return 业务产物工件
     */
    Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context);

    /**
     * 判断当前智能体是否支持指定的任务类型与资产域
     *
     * @param taskType 任务类型 (如 SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS)
     * @param assetCategory 资产类别 (如 FUND, STOCK)
     * @return 是否支持
     */
    default boolean supports(String taskType, String assetCategory) {
        if (taskType == null) {
            return false;
        }
        AgentCard card = agentCard();
        if (card == null || card.skills() == null || card.skills().isEmpty()) {
            return false;
        }
        String normalizedTask = taskType.trim().toUpperCase(Locale.ROOT);
        String normalizedAsset = assetCategory != null ? assetCategory.trim().toUpperCase(Locale.ROOT) : "FUND";

        return card.skills().stream().anyMatch(skill -> {
            List<String> tags = skill.tags();
            if (tags == null) {
                return false;
            }
            boolean taskMatch = tags.stream().anyMatch(t -> t.equalsIgnoreCase(normalizedTask));
            boolean assetMatch = tags.contains("ALL") || tags.stream().anyMatch(t -> t.equalsIgnoreCase(normalizedAsset));
            return taskMatch && assetMatch;
        });
    }
}

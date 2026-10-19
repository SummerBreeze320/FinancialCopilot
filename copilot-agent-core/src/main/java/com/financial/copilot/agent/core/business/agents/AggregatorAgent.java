package com.financial.copilot.agent.core.business.agents;

import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;

/**
 * <h1>投研产物最终聚合器接口 (AggregatorAgent)</h1>
 * <p>
 * 仅接收经确定性校验并通过 Reviewer 批准使用的产物，合成最终结构化专业投资研报。
 * 当遭遇降级决策 (DEGRADE) 时，在研报摘要与免责条款中显式披露数据限制与事实缺口。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface AggregatorAgent {

    /**
     * 无审查上下文的标准研报合成（默认直接委托至 {@link #executeWithReview} 并传入 null 审查决策）
     *
     * @param node 当前 DAG 节点元数据
     * @param input 节点输入产物集
     * @param context 节点执行上下文与取消令牌
     * @return 最终合成研报工件
     */
    default Artifact<FinalSynthesisReport> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        return executeWithReview(node, input, context, null);
    }

    /**
     * 结合跨证据审查裁决的研报合成与降级披露聚合
     *
     * @param node 当前 DAG 节点元数据
     * @param input 节点输入产物集
     * @param context 节点执行上下文与取消令牌
     * @param reviewDecision 跨证据审查决策（含降级披露或缺口说明，若为 null 则按无限制标准研报处理）
     * @return 最终合成研报工件
     */
    Artifact<FinalSynthesisReport> executeWithReview(GraphNode node, NodeInput input, NodeExecutionContext context, ReviewDecision reviewDecision);
}

package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.business.agents.AgentRoleCatalog;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <h1>确定性执行图规划校验器 (PlanValidator)</h1>
 * <p>
 * 保证模型生成的 TaskDAG 映射至 ExecutionGraph 时符合严格的安全与结构约束：
 * 拒绝成环、拒绝未注册角色、拒绝非根节点缺失输入绑定、拒绝注入保护字段（userId、tenantId、runId、计费金额）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class PlanValidator {

    private static final Set<String> PROTECTED_PARAMS = Set.of(
            "userid", "user_id", "tenantid", "tenant_id", "runid", "run_id", "points", "cost", "billing"
    );

    public PlanValidationResult validate(ExecutionGraph graph) {
        if (graph == null || graph.getNodes().isEmpty()) {
            return PlanValidationResult.failure(List.of("ExecutionGraph is null or contains no nodes"));
        }

        List<String> errors = new ArrayList<>();

        if (graph.hasCycle()) {
            errors.add("Cycle detected in ExecutionGraph: " + graph.getGraphId());
        }

        for (GraphNode node : graph.getNodes().values()) {
            if (!AgentRoleCatalog.supports(node.getTaskType())) {
                errors.add("No AgentScope role for task type: " + node.getTaskType());
            }

            Set<String> upstreams = graph.getUpstream(node.getNodeId());
            if (!upstreams.isEmpty() && (node.getInputBindings() == null || node.getInputBindings().isEmpty())) {
                errors.add("Non-root node requires explicit inputBindings: " + node.getNodeId());
            }

            if (node.getParams() != null) {
                for (String key : node.getParams().keySet()) {
                    if (key != null && PROTECTED_PARAMS.contains(key.toLowerCase())) {
                        errors.add("Node specifies protected parameter: " + key);
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            return PlanValidationResult.failure(errors);
        }
        return PlanValidationResult.success();
    }
}

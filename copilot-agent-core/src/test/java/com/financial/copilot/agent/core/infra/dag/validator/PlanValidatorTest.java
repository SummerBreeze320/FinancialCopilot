package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.model.InputBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("确定性执行图规划校验器 (PlanValidator) 测试")
class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @Test
    @DisplayName("合法图结构校验通过")
    void testValidGraphPasses() {
        ExecutionGraph graph = new ExecutionGraph("g1");
        GraphNode n1 = GraphNode.builder().nodeId("n1").name("筛选").taskType("SCREENING").build();
        GraphNode n2 = GraphNode.builder().nodeId("n2").name("分析").taskType("BATCH_ANALYSIS")
                .inputBindings(List.of(new InputBinding("candidates", "n1", ArtifactType.GENERAL, "$", true)))
                .build();

        graph.addNode(n1);
        graph.addNode(n2);
        graph.addEdge("n1", "n2");

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("拒绝包含环的非法图构建")
    void testRejectCyclicGraph() {
        ExecutionGraph graph = new ExecutionGraph("g_cycle");
        GraphNode n1 = GraphNode.builder().nodeId("n1").name("n1").taskType("SCREENING")
                .inputBindings(List.of(new InputBinding("in", "n2", ArtifactType.GENERAL, "$", true)))
                .build();
        GraphNode n2 = GraphNode.builder().nodeId("n2").name("n2").taskType("BATCH_ANALYSIS")
                .inputBindings(List.of(new InputBinding("in", "n1", ArtifactType.GENERAL, "$", true)))
                .build();

        graph.addNode(n1);
        graph.addNode(n2);
        graph.addEdge("n1", "n2");

        assertThatThrownBy(() -> graph.addEdge("n2", "n1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creates a cycle");
    }

    @Test
    @DisplayName("拒绝未注册角色的非法节点")
    void testRejectUnregisteredRole() {
        ExecutionGraph graph = new ExecutionGraph("g_bad_role");
        GraphNode n1 = GraphNode.builder().nodeId("n1").name("hacker").taskType("ARBITRARY_SHELL_EXEC").build();
        graph.addNode(n1);

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("No AgentScope role for task type: ARBITRARY_SHELL_EXEC"));
    }

    @Test
    @DisplayName("拒绝非根节点缺失 inputBindings")
    void testRejectMissingInputBindings() {
        ExecutionGraph graph = new ExecutionGraph("g_missing_binding");
        GraphNode n1 = GraphNode.builder().nodeId("n1").name("root").taskType("SCREENING").build();
        GraphNode n2 = GraphNode.builder().nodeId("n2").name("child").taskType("BATCH_ANALYSIS").build();
        graph.addNode(n1);
        graph.addNode(n2);
        graph.addEdge("n1", "n2");

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("inputBindings"));
    }

    @Test
    @DisplayName("拒绝模型试图注入受保护字段 (userId, tenantId, runId)")
    void testRejectProtectedFieldInjection() {
        ExecutionGraph graph = new ExecutionGraph("g_spoof");
        GraphNode n1 = GraphNode.builder().nodeId("n1").name("spoof").taskType("SCREENING")
                .params(Map.of("userId", 99999L, "tenantId", "evil"))
                .build();
        graph.addNode(n1);

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("protected parameter"));
    }
}

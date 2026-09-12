package com.financial.copilot.agent.core.dag.model;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.runtime.DependencyResolver;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputBindingTest {

    record Pool(List<String> fundCodes) {}

    @Test
    void bindingSelectsDeclaredProducerAndProjectsRecordField() {
        ArtifactStore store = new ArtifactStore();
        store.store("semiconductor", Artifact.of("a", ArtifactType.FUND_POOL, "semiconductor", new Pool(List.of("semi"))));
        store.store("energy", Artifact.of("b", ArtifactType.FUND_POOL, "energy", new Pool(List.of("energy"))));
        GraphNode node = GraphNode.builder().nodeId("compare")
                .inputBindings(List.of(new InputBinding("funds", "energy", ArtifactType.FUND_POOL, "$.fundCodes", true)))
                .build();

        NodeInput input = DependencyResolver.resolve(node, store);

        assertThat(input.require("funds", List.class)).containsExactly("energy");
    }

    @Test
    void bindingProjectsNestedMapAndRejectsWrongType() {
        ArtifactStore store = new ArtifactStore();
        store.store("source", Artifact.of("a", ArtifactType.GENERAL, "source", Map.of("outer", Map.of("value", 7))));
        GraphNode valid = GraphNode.builder().nodeId("valid")
                .inputBindings(List.of(new InputBinding("value", "source", ArtifactType.GENERAL, "$.outer.value", true)))
                .build();
        GraphNode invalid = GraphNode.builder().nodeId("invalid")
                .inputBindings(List.of(new InputBinding("value", "source", ArtifactType.FUND_POOL, "$", true)))
                .build();

        assertThat(DependencyResolver.resolve(valid, store).require("value", Integer.class)).isEqualTo(7);
        assertThatThrownBy(() -> DependencyResolver.resolve(invalid, store))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected FUND_POOL");
    }

    @Test
    void optionalMissingBindingIsEmptyButRequiredOneFails() {
        ArtifactStore store = new ArtifactStore();
        GraphNode optional = GraphNode.builder().nodeId("optional")
                .inputBindings(List.of(new InputBinding("funds", "missing", ArtifactType.FUND_POOL, "$", false)))
                .build();
        GraphNode required = GraphNode.builder().nodeId("required")
                .inputBindings(List.of(new InputBinding("funds", "missing", ArtifactType.FUND_POOL, "$", true)))
                .build();

        assertThat(DependencyResolver.resolve(optional, store).find("funds")).isEmpty();
        assertThatThrownBy(() -> DependencyResolver.resolve(required, store))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }
}

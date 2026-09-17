package com.financial.copilot.agent.tools.workspace;

import com.financial.copilot.agent.tools.model.ToolDefinition;
import com.financial.copilot.agent.tools.model.ToolExecuteRequest;
import com.financial.copilot.agent.tools.model.UITreeComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("工作台构建器测试")
class ConfiguredToolWorkspaceBuilderTest {

    @Test
    @DisplayName("验证将 ToolDefinition、入参与组件映射为 ToolWorkspacePayload")
    void buildsWorkspacePayload() {
        ComponentInstanceIdFactory idFactory = new ComponentInstanceIdFactory();
        ConfiguredToolWorkspaceBuilder builder = new ConfiguredToolWorkspaceBuilder(idFactory);

        ToolDefinition definition = ToolDefinition.builder()
                .id("compare_basic_info")
                .name("基金基础信息横向对比")
                .scope("COMPARISON")
                .build();

        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("compare_basic_info")
                .arguments(Map.of("windCodes", List.of("005827.OF", "163402.OF"), "endDate", "2026-09-15"))
                .build();

        UITreeComponent comp1 = UITreeComponent.builder()
                .id("FundInfoForDefault")
                .name("基金资料")
                .cardTitle("基本资料")
                .displayType("table")
                .data(List.of(Map.of("windCode", "005827.OF", "name", "易方达蓝筹精选")))
                .build();

        UITreeComponent comp2 = UITreeComponent.builder()
                .id("FundInfoForIndexFund")
                .name("指数基金资料")
                .cardTitle("指数基金")
                .displayType("table")
                .build();

        ToolWorkspacePayload payload = builder.build(definition, request, List.of(comp1, comp2));

        assertThat(payload).isNotNull();
        assertThat(payload.type()).isEqualTo("FUND_COMPARISON");
        assertThat(payload.components()).hasSize(2);
        assertThat(payload.components().get(0).id()).isEqualTo("FundInfoForDefault:005827.OF+163402.OF:2026-09-15");
        assertThat(payload.components().get(0).name()).isEqualTo("基金资料");
        assertThat(payload.components().get(0).data()).isNotEmpty();

        List<ToolWorkspaceReference> refs = builder.references(payload);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).items()).hasSize(2);
        assertThat(refs.get(0).items().get(0).name()).isEqualTo("基金资料");
        assertThat(refs.get(0).items().get(0).description()).isEqualTo("基本资料");
    }

    @Test
    @DisplayName("验证多次构建工作台时 referenceId 严格自增防冲突")
    void multipleBuildsIncrementReferenceIdMonotonically() {
        ComponentInstanceIdFactory idFactory = new ComponentInstanceIdFactory();
        ConfiguredToolWorkspaceBuilder builder = new ConfiguredToolWorkspaceBuilder(idFactory);

        ToolDefinition definition = ToolDefinition.builder()
                .id("fund_analysis_profile")
                .name("基金画像")
                .scope("ANALYSIS")
                .build();

        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("fund_analysis_profile")
                .arguments(Map.of("windCodes", List.of("005827.OF")))
                .build();

        ToolWorkspacePayload p1 = builder.build(definition, request, List.of());
        ToolWorkspacePayload p2 = builder.build(definition, request, List.of());
        ToolWorkspacePayload p3 = builder.build(definition, request, List.of());

        assertThat(p1.referenceId()).isEqualTo(1);
        assertThat(p2.referenceId()).isEqualTo(2);
        assertThat(p3.referenceId()).isEqualTo(3);
    }
}

package com.financial.copilot.agent.tools.configured.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("组件稳定实例 ID 生成测试")
class ComponentInstanceIdFactoryTest {

    @Test
    @DisplayName("验证根据组件 ID、标的列表与结束日期构建稳定复合 ID")
    void buildsStableIdFromComponentAndArguments() {
        ComponentInstanceIdFactory factory = new ComponentInstanceIdFactory();

        String id = factory.create("FundInfoForDefault", Map.of(
                "windCodes", List.of("005827.OF", "163402.OF"),
                "endDate", "2026-09-15"
        ));

        assertThat(id).isEqualTo("FundInfoForDefault:005827.OF+163402.OF:2026-09-15");
    }

    @Test
    @DisplayName("验证支持单代码与 reportDate")
    void buildsStableIdWithReportDate() {
        ComponentInstanceIdFactory factory = new ComponentInstanceIdFactory();

        String id = factory.create("MoneyBasicInfo", Map.of(
                "windCodes", "005827.OF",
                "reportDate", "2025-12-31"
        ));

        assertThat(id).isEqualTo("MoneyBasicInfo:005827.OF:2025-12-31");
    }
}

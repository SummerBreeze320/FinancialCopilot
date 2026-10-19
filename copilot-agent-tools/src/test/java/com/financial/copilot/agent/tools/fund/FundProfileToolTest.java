package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.business.fund.entity.FundInfo;
import com.financial.copilot.domain.business.fund.port.FundDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FundProfileTool 基金全维属性档案查询测试")
class FundProfileToolTest {

    private FundDataPort fundDataPort;
    private ObjectMapper objectMapper;
    private FundProfileTool tool;

    @BeforeEach
    void setUp() {
        fundDataPort = mock(FundDataPort.class);
        objectMapper = new ObjectMapper();
        tool = new FundProfileTool(fundDataPort, objectMapper);
    }

    @Test
    @DisplayName("按6位基金代码精确查询基金档案")
    void testGetFundProfileByCode() {
        FundInfo fund = FundInfo.builder()
                .fundCode("005827")
                .fundName("易方达蓝筹精选混合")
                .fundType("混合型")
                .managementCompanyId("易方达基金")
                .currentScaleBillion(new BigDecimal("420.5"))
                .trackingBenchmark("MSCI中国A股指数...")
                .build();
        when(fundDataPort.getFundByCode("005827")).thenReturn(Optional.of(fund));

        String json = tool.getFundProfile("005827");
        assertThat(json).contains("SUCCESS")
                .contains("005827")
                .contains("易方达蓝筹精选混合")
                .contains("混合型");
    }

    @Test
    @DisplayName("按中文名称关键词模糊搜索基金档案")
    void testGetFundProfileByKeyword() {
        FundInfo fund = FundInfo.builder()
                .fundCode("005827")
                .fundName("易方达蓝筹精选混合")
                .fundType("混合型")
                .managementCompanyId("易方达基金")
                .build();
        when(fundDataPort.searchFunds(eq("易方达蓝筹"), anyInt())).thenReturn(List.of(fund));

        String json = tool.getFundProfile("易方达蓝筹");
        assertThat(json).contains("SUCCESS")
                .contains("005827")
                .contains("易方达蓝筹精选混合");
    }

    @Test
    @DisplayName("未查找到基金时返回友好 NOT_FOUND 结果")
    void testGetFundProfileNotFound() {
        when(fundDataPort.searchFunds(eq("未知基金"), anyInt())).thenReturn(List.of());

        String json = tool.getFundProfile("未知基金");
        assertThat(json).contains("NOT_FOUND");
    }
}

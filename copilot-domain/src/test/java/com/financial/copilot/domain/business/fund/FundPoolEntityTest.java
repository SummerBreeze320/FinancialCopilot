package com.financial.copilot.domain.business.fund;

import com.financial.copilot.domain.business.fund.entity.FundPoolItem;
import com.financial.copilot.domain.business.stock.entity.StockWatchlistItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("FundPoolItem & StockWatchlistItem 领域实体测试")
class FundPoolEntityTest {

    @Test
    @DisplayName("验证基金池实体构建与字段完整性")
    void testFundPoolItemBuilder() {
        Instant now = Instant.now();
        FundPoolItem item = FundPoolItem.builder()
                .id(1L)
                .userId(100L)
                .fundCode("005827")
                .fundName("易方达蓝筹精选混合")
                .poolType("CORE")
                .targetWeight(new BigDecimal("0.2000"))
                .reason("优质消费与互联网龙头，近三年夏普比率领先")
                .tags("蓝筹,消费,高夏普")
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertNotNull(item);
        assertEquals(100L, item.getUserId());
        assertEquals("005827", item.getFundCode());
        assertEquals("CORE", item.getPoolType());
        assertEquals(new BigDecimal("0.2000"), item.getTargetWeight());
    }

    @Test
    @DisplayName("验证自选股实体构建与字段完整性")
    void testStockWatchlistItemBuilder() {
        Instant now = Instant.now();
        StockWatchlistItem item = StockWatchlistItem.builder()
                .id(2L)
                .userId(100L)
                .stockCode("600519")
                .stockName("贵州茅台")
                .groupName("白酒消费")
                .targetPrice(new BigDecimal("1850.00"))
                .notes("行业壁垒深厚，现金流充沛")
                .priority("HIGH")
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertNotNull(item);
        assertEquals(100L, item.getUserId());
        assertEquals("600519", item.getStockCode());
        assertEquals("白酒消费", item.getGroupName());
        assertEquals(new BigDecimal("1850.00"), item.getTargetPrice());
        assertEquals("HIGH", item.getPriority());
    }
}

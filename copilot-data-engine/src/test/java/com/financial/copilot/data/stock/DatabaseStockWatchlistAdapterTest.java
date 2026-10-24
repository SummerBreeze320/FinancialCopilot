package com.financial.copilot.data.stock;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.financial.copilot.data.stock.adapter.DatabaseStockWatchlistAdapter;
import com.financial.copilot.data.stock.mapper.StockWatchlistMapper;
import com.financial.copilot.data.stock.po.StockWatchlistPO;
import com.financial.copilot.domain.business.stock.entity.StockWatchlistItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DatabaseStockWatchlistAdapter 自选股持久化适配器测试")
class DatabaseStockWatchlistAdapterTest {

    private StockWatchlistMapper mapper;
    private DatabaseStockWatchlistAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = mock(StockWatchlistMapper.class);
        adapter = new DatabaseStockWatchlistAdapter(mapper);
    }

    @Test
    @DisplayName("新增自选股记录成功")
    void testAddStockWatchlist() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(StockWatchlistPO.class))).thenAnswer(inv -> {
            StockWatchlistPO po = inv.getArgument(0);
            po.setId(88L);
            return 1;
        });

        StockWatchlistItem item = StockWatchlistItem.builder()
                .userId(1L)
                .stockCode("600519")
                .stockName("贵州茅台")
                .groupName("白酒")
                .targetPrice(new BigDecimal("1800"))
                .build();

        StockWatchlistItem saved = adapter.addOrUpdate(item);
        assertNotNull(saved);
        assertEquals(88L, saved.getId());
        verify(mapper, times(1)).insert(any(StockWatchlistPO.class));
    }

    @Test
    @DisplayName("按用户和分组查询自选股")
    void testListByUserAndGroup() {
        StockWatchlistPO po = StockWatchlistPO.builder()
                .id(1L)
                .userId(1L)
                .stockCode("600519")
                .stockName("贵州茅台")
                .groupName("白酒")
                .build();

        when(mapper.selectList(any())).thenReturn(List.of(po));

        List<StockWatchlistItem> list = adapter.listByUser(1L, "白酒");
        assertEquals(1, list.size());
        assertEquals("600519", list.get(0).getStockCode());
    }

    @Test
    @DisplayName("清空自选股分组")
    void testClearGroup() {
        when(mapper.delete(any(Wrapper.class))).thenReturn(5);
        int deleted = adapter.clear(1L, "白酒");
        assertEquals(5, deleted);
    }
}

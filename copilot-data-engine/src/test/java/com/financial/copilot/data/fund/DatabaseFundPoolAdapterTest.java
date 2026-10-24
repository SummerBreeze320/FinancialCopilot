package com.financial.copilot.data.fund;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.financial.copilot.data.fund.adapter.DatabaseFundPoolAdapter;
import com.financial.copilot.data.fund.mapper.FundPoolMapper;
import com.financial.copilot.data.fund.po.FundPoolPO;
import com.financial.copilot.domain.business.fund.entity.FundPoolItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DatabaseFundPoolAdapter 基金池持久化适配器测试")
class DatabaseFundPoolAdapterTest {

    private FundPoolMapper mapper;
    private DatabaseFundPoolAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = mock(FundPoolMapper.class);
        adapter = new DatabaseFundPoolAdapter(mapper);
    }

    @Test
    @DisplayName("新增基金池记录成功")
    void testAddOrUpdateInsert() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(FundPoolPO.class))).thenAnswer(inv -> {
            FundPoolPO po = inv.getArgument(0);
            po.setId(99L);
            return 1;
        });

        FundPoolItem item = FundPoolItem.builder()
                .userId(1L)
                .fundCode("005827")
                .fundName("易方达蓝筹")
                .poolType("CORE")
                .targetWeight(new BigDecimal("0.15"))
                .build();

        FundPoolItem saved = adapter.addOrUpdate(item);
        assertNotNull(saved);
        assertEquals(99L, saved.getId());
        verify(mapper, times(1)).insert(any(FundPoolPO.class));
    }

    @Test
    @DisplayName("按用户查询基金池列表")
    void testListByUser() {
        FundPoolPO po = FundPoolPO.builder()
                .id(1L)
                .userId(1L)
                .fundCode("005827")
                .fundName("易方达蓝筹")
                .poolType("CORE")
                .targetWeight(new BigDecimal("0.15"))
                .build();

        when(mapper.selectList(any())).thenReturn(List.of(po));

        List<FundPoolItem> list = adapter.listByUser(1L, "CORE");
        assertEquals(1, list.size());
        assertEquals("005827", list.get(0).getFundCode());
    }

    @Test
    @DisplayName("移除基金池标的")
    void testRemove() {
        when(mapper.delete(any(Wrapper.class))).thenReturn(1);
        boolean ok = adapter.remove(1L, "005827", "CORE");
        assertTrue(ok);
    }
}

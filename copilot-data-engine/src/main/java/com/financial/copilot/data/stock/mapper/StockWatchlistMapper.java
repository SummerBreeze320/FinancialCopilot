package com.financial.copilot.data.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.stock.po.StockWatchlistPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>自选股数据访问映射器 (StockWatchlistMapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface StockWatchlistMapper extends BaseMapper<StockWatchlistPO> {
}

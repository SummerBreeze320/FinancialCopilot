package com.financial.copilot.data.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.stock.po.StockDailyKlinePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>股票日 K 线 MyBatis-Plus 数据访问 Mapper</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface StockDailyKlineMapper extends BaseMapper<StockDailyKlinePO> {
}

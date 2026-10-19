package com.financial.copilot.data.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.stock.po.StockDividendPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>股票分红历史数据映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface StockDividendMapper extends BaseMapper<StockDividendPO> {
}

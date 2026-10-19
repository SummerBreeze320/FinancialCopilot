package com.financial.copilot.data.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.stock.po.StockFinancialQuarterlyPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>股票季度财务指标数据映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface StockFinancialMapper extends BaseMapper<StockFinancialQuarterlyPO> {
}

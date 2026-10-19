package com.financial.copilot.data.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.stock.po.StockTopShareholderPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>股票十大股东数据映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface StockShareholderMapper extends BaseMapper<StockTopShareholderPO> {
}

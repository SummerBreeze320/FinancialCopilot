package com.financial.copilot.data.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.stock.po.StockInfoPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>股票基础信息持久化映射器 Mapper (Stock Info Mapper)</h1>
 * <p>
 * 继承 MyBatis-Plus 的 {@link BaseMapper}，无需编写任何 XML 即可获取高性能 CRUD 及条件构造器能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface StockInfoMapper extends BaseMapper<StockInfoPO> {
}

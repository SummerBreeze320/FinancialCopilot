package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundQuarterlyHoldingPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>基金季度重仓持股明细数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code fund_quarterly_holdings} 表的重仓明细增删改查及季度持仓透视检索能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface FundQuarterlyHoldingMapper extends BaseMapper<FundQuarterlyHoldingPO> {
}

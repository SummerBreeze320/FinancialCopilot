package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundManagerPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>基金经理数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code fund_manager} 表的增删改查、从业履历及管理规模检索能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface FundManagerMapper extends BaseMapper<FundManagerPO> {
}

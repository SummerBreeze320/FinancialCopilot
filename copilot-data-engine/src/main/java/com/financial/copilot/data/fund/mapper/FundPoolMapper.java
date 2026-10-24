package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundPoolPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>基金池数据访问映射器 (FundPoolMapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface FundPoolMapper extends BaseMapper<FundPoolPO> {
}

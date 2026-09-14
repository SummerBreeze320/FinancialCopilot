package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundNavHistoryPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>基金历史净值时序数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code fund_nav_history} 表的历史净值时序插入、批量更新与区间检索能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface FundNavHistoryMapper extends BaseMapper<FundNavHistoryPO> {
}

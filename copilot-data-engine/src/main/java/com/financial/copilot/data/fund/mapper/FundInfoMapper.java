package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundInfoPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>基金基础信息数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code fund_info} 表的增删改查、条件初筛与主键索引检索能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface FundInfoMapper extends BaseMapper<FundInfoPO> {
}

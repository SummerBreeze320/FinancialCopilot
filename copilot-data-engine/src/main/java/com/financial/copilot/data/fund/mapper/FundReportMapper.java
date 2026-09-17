package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundReportPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>基金定性定期报告数据访问映射器 (MyBatis-Plus MySQL Mapper)</h1>
 * <p>
 * 提供针对 {@code fund_report} 表的标准 MySQL 关系型数据读写。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface FundReportMapper extends BaseMapper<FundReportPO> {
}

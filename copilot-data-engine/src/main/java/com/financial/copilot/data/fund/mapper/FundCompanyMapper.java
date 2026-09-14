package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundCompanyPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>基金公司数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code fund_company} 表的增删改查及公司层级管理规模查询能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface FundCompanyMapper extends BaseMapper<FundCompanyPO> {
}

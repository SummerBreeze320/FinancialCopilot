package com.financial.copilot.domain.port;

import com.financial.copilot.common.dto.FundMetricsDTO;
import com.financial.copilot.common.dto.FundScreeningCriteria;
import com.financial.copilot.domain.entity.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 基金数据访问服务 SPI 端口契约
 * 核心层和 Agent 仅依赖此接口，不感知底层是 PostgreSQL 还是企业内部微服务/API。
 */
public interface FundDataPort {

    /**
     * 根据基金代码获取单只基金基础信息
     */
    Optional<FundInfo> getFundByCode(String fundCode);

    /**
     * 根据多维条件执行智能筛选
     */
    List<FundInfo> screenFunds(FundScreeningCriteria criteria);

    /**
     * 获取指定时间区间的每日复权净值时序 (按日期升序)
     */
    List<FundNavHistory> getNavHistory(String fundCode, LocalDate startDate, LocalDate endDate);

    /**
     * 获取指定报告期的前十大重仓股票持仓明细 (按持仓比例降序)
     */
    List<FundQuarterlyHolding> getHoldings(String fundCode, String reportQuarter);

    /**
     * 计算并获取单只基金在指定区间的全景量化指标
     */
    FundMetricsDTO getFundMetrics(String fundCode, LocalDate startDate, LocalDate endDate);

    /**
     * 根据经理 ID 获取基金经理档案
     */
    Optional<FundManager> getManagerById(String managerId);

    /**
     * 根据基金经理姓名模糊搜索经理档案
     */
    List<FundManager> searchManagersByName(String managerName);

    /**
     * 获取指定经理在管/历任的基金列表
     */
    List<FundInfo> getFundsByManagerId(String managerId);

    /**
     * 根据公司 ID 获取基金公司档案
     */
    Optional<FundCompany> getCompanyById(String companyId);
}

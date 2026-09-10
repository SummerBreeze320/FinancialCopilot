package com.financial.copilot.domain.fund.port;

import com.financial.copilot.common.fund.dto.FundMetricsDTO;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.domain.fund.entity.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * <h1>公募基金数据访问 SPI 端口契约</h1>
 * <p>
 * 定义系统与具体数据源之间的解耦隔离层。上层 AgentScope 智能体与业务编排器仅依赖此抽象契约，
 * 底层可自由替换为 PostgreSQL 关系库、MongoDB、企业内网数据总线或第三方商业金融 API。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface FundDataPort {

    /**
     * 根据基金代码获取单只基金的核心基础信息
     *
     * @param fundCode 6位基金代码 (如: 005827)
     * @return 包含基金基础信息的 Optional 容器
     */
    Optional<FundInfo> getFundByCode(String fundCode);

    /**
     * 根据多维定量与分类约束执行基金智能筛选
     *
     * @param criteria 强类型选基 DSL 条件对象
     * @return 命中条件的候选基金标的列表
     */
    List<FundInfo> screenFunds(FundScreeningCriteria criteria);

    /**
     * 获取指定时间区间的每日复权净值时序数据 (按日期递增升序排列)
     *
     * @param fundCode  6位基金代码
     * @param startDate 起始日期 (可选)
     * @param endDate   截止日期 (可选)
     * @return 历史复权净值时序列表
     */
    List<FundNavHistory> getNavHistory(String fundCode, LocalDate startDate, LocalDate endDate);

    /**
     * 获取指定报告期的前十大重仓股票持仓明细 (按持仓比例降序排列)
     *
     * @param fundCode      6位基金代码
     * @param reportQuarter 季度标识 (如 "2024Q2"，为空则默认最新季度)
     * @return 前十大重仓股票列表
     */
    List<FundQuarterlyHolding> getHoldings(String fundCode, String reportQuarter);

    /**
     * 依据纯 Java 原生数学公式计算单只基金在指定区间的全景量化指标 (收益/回撤/夏普/卡玛/集中度)
     *
     * @param fundCode  6位基金代码
     * @param startDate 统计区间起始日期
     * @param endDate   统计区间截止日期
     * @return 纯数学高精度计算产出的量化指标 DTO
     */
    FundMetricsDTO getFundMetrics(String fundCode, LocalDate startDate, LocalDate endDate);

    /**
     * 根据经理唯一标识获取基金经理档案
     *
     * @param managerId 经理工号或系统 ID
     * @return 经理档案 Optional
     */
    Optional<FundManager> getManagerById(String managerId);

    /**
     * 根据姓名模糊搜索基金经理档案
     *
     * @param managerName 经理姓名关键字 (如 "张坤")
     * @return 匹配命中的基金经理列表
     */
    List<FundManager> searchManagersByName(String managerName);

    /**
     * 获取指定基金经理名下在管或历任的基金产品列表
     *
     * @param managerId 经理 ID
     * @return 该经理管理的基金列表
     */
    List<FundInfo> getFundsByManagerId(String managerId);

    /**
     * 根据公司 ID 获取基金管理公司全景档案
     *
     * @param companyId 公司唯一标识
     * @return 基金公司档案 Optional
     */
    Optional<FundCompany> getCompanyById(String companyId);
}

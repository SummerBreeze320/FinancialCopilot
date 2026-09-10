package com.financial.copilot.domain.futures.port;

import com.financial.copilot.domain.futures.entity.FuturesContractInfo;

import java.util.List;
import java.util.Optional;

/**
 * <h1>期货领域数据网关 Port 接口 (Futures Data Port)</h1>
 * <p>
 * 职责：定义期货标的数据访问的防腐隔离契约，由底层数据适配器实现。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface FuturesDataPort {

    /**
     * 根据期货合约代码精确查询合约信息
     *
     * @param contractCode 合约代码
     * @return 合约实体封装
     */
    Optional<FuturesContractInfo> getContractByCode(String contractCode);

    /**
     * 根据品种代码查询活跃主力合约列表
     *
     * @param commodityCode 品种简称（如 "IF"、"RB"）
     * @return 合约列表
     */
    List<FuturesContractInfo> getActiveContracts(String commodityCode);
}

package com.financial.copilot.domain.wealth.port;

import com.financial.copilot.domain.wealth.entity.WealthProductInfo;

import java.util.List;
import java.util.Optional;

/**
 * <h1>银行理财领域数据网关 Port 接口 (Wealth Data Port)</h1>
 * <p>
 * 职责：定义银行理财产品数据访问的防腐隔离契约。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface WealthDataPort {

    /**
     * 根据产品代码精确查询理财产品信息
     *
     * @param productCode 产品登记编码
     * @return 产品实体可空包装
     */
    Optional<WealthProductInfo> getProductByCode(String productCode);

    /**
     * 根据风险等级与发行机构筛选理财产品
     *
     * @param riskLevel 风险等级 (如 "PR2")
     * @param issuer    发行机构关键字
     * @return 命中的理财产品列表
     */
    List<WealthProductInfo> queryProducts(String riskLevel, String issuer);
}

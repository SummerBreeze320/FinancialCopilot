package com.financial.copilot.domain.business.fund.port;

import com.financial.copilot.domain.business.fund.entity.FundPoolItem;

import java.util.List;
import java.util.Optional;

/**
 * <h1>基金池数据访问 SPI 端口契约 (FundPoolPort)</h1>
 * <p>定义用户自选基金池的增删改查隔离操作。</p>
 *
 * @author FinancialCopilot
 */
public interface FundPoolPort {

    /**
     * 新增或更新基金池标的
     *
     * @param item 标的信息
     * @return 插入或更新后的实体
     */
    FundPoolItem addOrUpdate(FundPoolItem item);

    /**
     * 从基金池中剔除指定标的
     *
     * @param userId 用户 ID
     * @param fundCode 基金代码
     * @param poolType 池类型 (为 null 时删除全部池中的该代码)
     * @return 是否成功移除
     */
    boolean remove(Long userId, String fundCode, String poolType);

    /**
     * 清空指定类型的基金池
     *
     * @param userId 用户 ID
     * @param poolType 池类型 (为 null 时清空全部池)
     * @return 清空的记录数
     */
    int clear(Long userId, String poolType);

    /**
     * 查询用户单只基金在池中的详情
     *
     * @param userId 用户 ID
     * @param fundCode 基金代码
     * @param poolType 池类型
     * @return 标的详情 Optional
     */
    Optional<FundPoolItem> findByUserAndCode(Long userId, String fundCode, String poolType);

    /**
     * 列出用户指定分类池中的全部基金
     *
     * @param userId 用户 ID
     * @param poolType 池类型 (为 null 则查询用户全部基金池)
     * @return 标的列表
     */
    List<FundPoolItem> listByUser(Long userId, String poolType);
}

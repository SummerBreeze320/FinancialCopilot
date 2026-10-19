package com.financial.copilot.domain.business.stock.port;

import com.financial.copilot.domain.business.stock.entity.StockWatchlistItem;

import java.util.List;
import java.util.Optional;

/**
 * <h1>自选股数据访问 SPI 端口契约 (StockWatchlistPort)</h1>
 * <p>定义用户自选股的增删改查隔离操作。</p>
 *
 * @author FinancialCopilot
 */
public interface StockWatchlistPort {

    /**
     * 新增或更新自选股票
     *
     * @param item 标的信息
     * @return 插入或更新后的实体
     */
    StockWatchlistItem addOrUpdate(StockWatchlistItem item);

    /**
     * 从自选股中剔除指定标的
     *
     * @param userId 用户 ID
     * @param stockCode 股票代码
     * @param groupName 分组名称 (为 null 则从全部自选分组中移除)
     * @return 是否成功移除
     */
    boolean remove(Long userId, String stockCode, String groupName);

    /**
     * 清空指定分组下的自选股
     *
     * @param userId 用户 ID
     * @param groupName 分组名称 (为 null 则清空用户全部自选股)
     * @return 清空的记录数
     */
    int clear(Long userId, String groupName);

    /**
     * 查询单只股票在自选列表中的详情
     *
     * @param userId 用户 ID
     * @param stockCode 股票代码
     * @param groupName 分组名称
     * @return 标的详情 Optional
     */
    Optional<StockWatchlistItem> findByUserAndCode(Long userId, String stockCode, String groupName);

    /**
     * 列出用户指定分组下的全部自选股
     *
     * @param userId 用户 ID
     * @param groupName 分组名称 (为 null 则查询用户全部自选股)
     * @return 自选股列表
     */
    List<StockWatchlistItem> listByUser(Long userId, String groupName);
}

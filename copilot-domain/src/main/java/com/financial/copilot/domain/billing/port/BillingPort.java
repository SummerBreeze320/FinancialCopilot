package com.financial.copilot.domain.billing.port;

import com.financial.copilot.domain.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.billing.entity.*;

import java.util.List;
import java.util.Optional;

/**
 * <h1>算力计费、钱包与充值持久化端口契约 (Billing & Wallet Port)</h1>
 * <p>
 * 职责：遵循 DDD 六边形架构理念，定义商业化钱包资产、扣费对账、套餐上架与订单流水的数据访问规范。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface BillingPort {

    /**
     * 查询或初始化用户的算力钱包
     *
     * @param userId   系统用户 ID
     * @param tenantId 租户或机构 ID
     * @return 钱包领域实体
     */
    UserWallet getOrCreateWallet(Long userId, String tenantId);

    /**
     * 原子扣减用户钱包可用算力点数（具备乐观锁并发防超扣）
     *
     * @param userId         用户 ID
     * @param pointsToDeduct 待扣减点数
     * @return true 扣减成功，false 余额不足或并发版本冲突
     */
    boolean deductPoints(Long userId, long pointsToDeduct);

    /**
     * 充值到账：原子增加用户钱包可用算力点数与累计充值总额
     *
     * @param userId      用户 ID
     * @param pointsToAdd 到账总点数
     */
    void addRechargePoints(Long userId, long pointsToAdd);

    /**
     * 获取指定模型的生效定价规则
     *
     * @param modelName 模型名称标识 (如 deepseek-chat, deepseek-reasoner)
     * @return 匹配的定价规格（若无则返回默认基准定价）
     */
    Optional<ModelPricing> getPricing(String modelName);

    /**
     * 获取平台所有上架模型的定价规格矩阵
     *
     * @return 定价列表
     */
    List<ModelPricing> getAllPricing();

    /**
     * 持久化记录一条不可篡改的 Token 消费对账明细
     *
     * @param ledger 流水实体
     */
    void recordUsageLedger(TokenUsageLedger ledger);

    /**
     * 分页检索用户的 Token 消费明细
     *
     * @param userId   用户 ID
     * @param offset   偏移量
     * @param limit    每页数量
     * @param model    过滤模型名称（可选）
     * @param taskType 过滤步骤类型（可选）
     * @return 消费流水列表
     */
    List<TokenUsageLedger> queryLedger(Long userId, int offset, int limit, String model, String taskType);

    /**
     * 统计用户消费流水总条数
     *
     * @param userId   用户 ID
     * @param model    过滤模型名称（可选）
     * @param taskType 过滤步骤类型（可选）
     * @return 总记录数
     */
    long countLedger(Long userId, String model, String taskType);

    /**
     * 获取当前所有上架生效的充值规格套餐列表
     *
     * @return 套餐列表（按 sortOrder 正序）
     */
    List<RechargePackage> listActivePackages();

    /**
     * 按 ID 检索指定的充值套餐规格
     *
     * @param packageId 套餐 ID
     * @return 套餐实体
     */
    Optional<RechargePackage> getPackageById(Long packageId);

    /**
     * 创建并保存充值交易订单
     *
     * @param order 订单实体
     */
    void saveOrder(RechargeOrder order);

    /**
     * 根据交易订单号查询订单
     *
     * @param orderNo 唯一订单号
     * @return 订单实体
     */
    Optional<RechargeOrder> getOrderByNo(String orderNo);

    Optional<RechargeOrder> getOrderByNoForUpdate(String orderNo);

    /**
     * 更新订单状态与支付信息
     *
     * @param order 订单实体
     */
    void updateOrder(RechargeOrder order);

    /**
     * 统计用户过去指定天数的每日 Token 消耗与点数扣减走势
     *
     * @param userId 用户 ID
     * @param days   统计回溯天数 (如 7 或 30)
     * @return 时序聚合趋势点列表
     */
    List<UsageTrendPointDTO> getUsageTrend(Long userId, int days);
}

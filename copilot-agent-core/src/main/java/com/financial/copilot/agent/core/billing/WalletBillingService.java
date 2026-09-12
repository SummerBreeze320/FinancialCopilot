package com.financial.copilot.agent.core.billing;

import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.domain.billing.dto.TokenDeductionResult;
import com.financial.copilot.domain.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.billing.dto.WalletDTO;
import com.financial.copilot.domain.billing.entity.*;
import com.financial.copilot.domain.billing.port.BillingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <h1>算力计量计费与账户钱包业务服务 (Wallet Billing Service)</h1>
 * <p>
 * 职责：
 * 1. 投研请求前置配额探测与欠费拦截 (checkBalance)；
 * 2. 多模型阶梯计价折算与原子并发扣费 (deductTokenPoints)；
 * 3. 在线充值套餐下单与支付回调到账闭环 (createOrder & payCallback)；
 * 4. 账户钱包资产、消费对账清单与时序走势查询。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Service
public class WalletBillingService {

    private final BillingPort billingPort;

    public WalletBillingService(BillingPort billingPort) {
        this.billingPort = billingPort;
    }

    /**
     * 前置配额与余额探测：若钱包状态异常或余额低于门槛，抛出 {@link WalletInsufficientException}
     *
     * @param userId             用户 ID
     * @param minThresholdPoints 最低起步门槛点数（默认通常为 100 点）
     */
    public void checkBalance(Long userId, long minThresholdPoints) {
        Long uid = Objects.requireNonNull(userId, "userId");
        UserWallet wallet = billingPort.getOrCreateWallet(uid, null);
        if (!wallet.hasSufficientBalance(minThresholdPoints)) {
            log.warn("[WALLET-PRECHECK] 拦截调用：用户算力余额不足: userId={}, balance={}, required={}",
                    uid, wallet.getBalancePoints(), minThresholdPoints);
            throw new WalletInsufficientException(uid, wallet.getBalancePoints(), minThresholdPoints);
        }
    }

    /**
     * 根据模型标识与 Token 规模计算应扣算力点数
     *
     * @param model            模型名称
     * @param promptTokens     输入 Token 数
     * @param completionTokens 输出 Token 数
     * @return 应扣算力点数
     */
    public long calculatePoints(String model, int promptTokens, int completionTokens) {
        ModelPricing pricing = billingPort.getPricing(model)
                .orElseThrow(() -> new IllegalStateException("模型未配置有效定价: " + model));
        return pricing.calculatePoints(promptTokens, completionTokens);
    }

    /**
     * 投研任务完成后的原子扣费与对账流水记录
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param taskType         步骤类型
     * @param provider         实际使用厂商
     * @param model            实际使用模型
     * @param promptTokens     输入 Token 数
     * @param completionTokens 输出 Token 数
     * @param latencyMs        响应耗时
     * @return 扣费结算结果
     */
    @Transactional
    public TokenDeductionResult deductTokenPoints(Long userId, String sessionId, String taskType,
                                                  String provider, String model,
                                                  int promptTokens, int completionTokens,
                                                  long latencyMs) {
        Long uid = Objects.requireNonNull(userId, "userId");
        if (promptTokens < 0 || completionTokens < 0) throw new IllegalArgumentException("Negative token usage");
        int total = Math.addExact(promptTokens, completionTokens);
        long pointsToDeduct = calculatePoints(model, promptTokens, completionTokens);

        boolean deductSuccess = billingPort.deductPoints(uid, pointsToDeduct);
        if (!deductSuccess) {
            throw new WalletInsufficientException(uid, billingPort.getOrCreateWallet(uid, null).getBalancePoints(), pointsToDeduct);
        }

        // 记账流水入库
        TokenUsageLedger ledger = TokenUsageLedger.builder()
                .userId(uid)
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString())
                .taskType(taskType != null ? taskType : "GENERAL_QA")
                .provider(provider != null ? provider : "DEEPSEEK")
                .model(model != null ? model : "deepseek-chat")
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(total)
                .consumedPoints(pointsToDeduct)
                .latencyMs((int) latencyMs)
                .createdAt(LocalDateTime.now())
                .build();
        billingPort.recordUsageLedger(ledger);

        UserWallet updatedWallet = billingPort.getOrCreateWallet(uid, null);
        double costCny = BigDecimal.valueOf(pointsToDeduct)
                .divide(BigDecimal.valueOf(10000), 4, RoundingMode.HALF_UP)
                .doubleValue();

        log.info("[WALLET-DEDUCT] 结算完成: user={}, model={}, tokens={}, points={}, cost=¥{}, remaining={}",
                uid, model, total, pointsToDeduct, costCny, updatedWallet.getBalancePoints());

        return TokenDeductionResult.builder()
                .success(deductSuccess)
                .provider(provider)
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(total)
                .consumedPoints(pointsToDeduct)
                .estimatedCostCny(costCny)
                .remainingBalancePoints(updatedWallet.getBalancePoints())
                .latencyMs(latencyMs)
                .message(deductSuccess ? "扣费成功" : "余额不足或并发扣减冲突")
                .build();
    }

    /**
     * 发起充值订单创建
     *
     * @param userId     用户 ID
     * @param packageId  选购套餐 ID
     * @param payChannel 支付方式 (WECHAT, ALIPAY, BANK)
     * @return 待支付订单实体
     */
    public RechargeOrder createOrder(Long userId, Long packageId, String payChannel) {
        if (payChannel != null && !"ALIPAY".equalsIgnoreCase(payChannel)) {
            throw new IllegalArgumentException("仅支持支付宝充值");
        }
        Long uid = Objects.requireNonNull(userId, "userId");
        RechargePackage pkg = billingPort.getPackageById(packageId)
                .orElseThrow(() -> new IllegalArgumentException("指定的充值规格套餐不存在: packageId=" + packageId));

        String orderNo = "ORD" + UUID.randomUUID().toString().replace("-", "");

        RechargeOrder order = RechargeOrder.builder()
                .orderNo(orderNo)
                .userId(uid)
                .packageId(pkg.getId())
                .payAmountCny(pkg.getPriceCny())
                .targetPoints(pkg.getTotalPoints())
                .payChannel("ALIPAY")
                .orderStatus("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        billingPort.saveOrder(order);
        log.info("[WALLET-ORDER] 创建充值订单成功: orderNo={}, package={}, amount=¥{}, targetPoints={}",
                orderNo, pkg.getPackageName(), pkg.getPriceCny(), pkg.getTotalPoints());
        return order;
    }

    /**
     * 第三方支付网关异步回调处理（验签与到账入库）
     *
     * @param orderNo            业务订单号
     * @param thirdPartyTradeNo 第三方流水号
     * @return 已完成订单实体
     */
    @Transactional
    public RechargeOrder payCallback(String orderNo, String thirdPartyTradeNo, BigDecimal paidAmount) {
        if (thirdPartyTradeNo == null || thirdPartyTradeNo.isBlank() || thirdPartyTradeNo.length() > 100) {
            throw new IllegalArgumentException("Invalid payment trade number");
        }
        RechargeOrder order = billingPort.getOrderByNoForUpdate(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("未找到待处理的充值订单: orderNo=" + orderNo));

        if (!"ALIPAY".equals(order.getPayChannel()) || paidAmount == null
                || order.getPayAmountCny().compareTo(paidAmount) != 0) {
            throw new IllegalArgumentException("Payment channel or amount mismatch");
        }

        if ("PAID".equalsIgnoreCase(order.getOrderStatus())) {
            if (!thirdPartyTradeNo.equals(order.getThirdPartyTradeNo())) {
                throw new IllegalArgumentException("Conflicting payment trade number");
            }
            log.info("[WALLET-CALLBACK] 订单已处于支付成功状态，幂等放行: orderNo={}", orderNo);
            return order;
        }

        if (!"PENDING".equals(order.getOrderStatus())) throw new IllegalArgumentException("Order is not payable");
        order.setOrderStatus("PAID");
        order.setThirdPartyTradeNo(thirdPartyTradeNo);
        order.setPaidAt(LocalDateTime.now());

        // 原子给用户钱包增加可用算力点
        billingPort.addRechargePoints(order.getUserId(), order.getTargetPoints());
        billingPort.updateOrder(order);

        log.info("[WALLET-CALLBACK] 充值订单到账处理完成: orderNo={}, userId={}, pointsAdded={}",
                orderNo, order.getUserId(), order.getTargetPoints());
        return order;
    }

    /**
     * 查询当前用户钱包资产与可用点数
     *
     * @param userId 用户 ID
     * @return 钱包传输对象
     */
    public WalletDTO getWallet(Long userId) {
        Long uid = Objects.requireNonNull(userId, "userId");
        UserWallet wallet = billingPort.getOrCreateWallet(uid, null);
        return WalletDTO.fromEntity(wallet);
    }

    /**
     * 获取所有已上架充值套餐列表
     *
     * @return 套餐列表
     */
    public List<RechargePackage> listPackages() {
        return billingPort.listActivePackages();
    }

    /**
     * 获取所有生效的模型定价矩阵
     *
     * @return 定价列表
     */
    public List<ModelPricing> listPricing() {
        return billingPort.getAllPricing();
    }

    /**
     * 分页查询用户 Token 消费记账流水
     *
     * @param userId   用户 ID
     * @param page     页码（从 1 开始）
     * @param size     每页条数
     * @param model    模型筛选
     * @param taskType 任务类型筛选
     * @return 分页结果映射 (total, list, page, size)
     */
    public Map<String, Object> getLedger(Long userId, int page, int size, String model, String taskType) {
        Long uid = Objects.requireNonNull(userId, "userId");
        int currentPage = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(size, 100));
        int offset = (currentPage - 1) * pageSize;

        List<TokenUsageLedger> list = billingPort.queryLedger(uid, offset, pageSize, model, taskType);
        long total = billingPort.countLedger(uid, model, taskType);

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("page", currentPage);
        result.put("size", pageSize);
        result.put("list", list);
        return result;
    }

    /**
     * 获取过去 N 天每日消耗走势时序数据
     *
     * @param userId 用户 ID
     * @param days   天数 (如 7 或 30)
     * @return 时序走势点集合
     */
    public List<UsageTrendPointDTO> getUsageTrend(Long userId, int days) {
        Long uid = Objects.requireNonNull(userId, "userId");
        return billingPort.getUsageTrend(uid, days);
    }

    /**
     * 新用户注册自动赠送初始体验算力包
     *
     * @param userId     用户 ID
     * @param giftPoints 赠送算力点数 (例如 10,000 点)
     */
    @Transactional
    public void grantInitialTrialPoints(Long userId, long giftPoints) {
        if (userId == null || giftPoints <= 0) return;
        billingPort.getOrCreateWallet(userId, null);
        billingPort.addRechargePoints(userId, giftPoints);
        log.info("[WALLET-GIFT] 新用户注册成功，赠送初始体验算力点: userId={}, points={}", userId, giftPoints);
    }
}

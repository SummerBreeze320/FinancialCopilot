package com.financial.copilot.data.billing.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.copilot.data.billing.mapper.*;
import com.financial.copilot.data.billing.po.*;
import com.financial.copilot.domain.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.billing.entity.*;
import com.financial.copilot.domain.billing.port.BillingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <h1>基于 MyBatis-Plus 与 PostgreSQL 的商业化计量计费数据适配器</h1>
 * <p>
 * 实现领域层 {@link BillingPort} SPI 契约。
 * 提供原子乐观锁防超扣、流水不可篡改落库、阶梯定价解析、套餐与订单状态流转，
 * 数据库异常直接传播，资金与流水不进行内存降级。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class DatabaseBillingAdapter implements BillingPort {

    private final UserWalletMapper walletMapper;
    private final ModelPricingMapper pricingMapper;
    private final TokenUsageLedgerMapper ledgerMapper;
    private final RechargePackageMapper packageMapper;
    private final RechargeOrderMapper orderMapper;


    public DatabaseBillingAdapter(UserWalletMapper walletMapper,
                                  ModelPricingMapper pricingMapper,
                                  TokenUsageLedgerMapper ledgerMapper,
                                  RechargePackageMapper packageMapper,
                                  RechargeOrderMapper orderMapper) {
        this.walletMapper = walletMapper;
        this.pricingMapper = pricingMapper;
        this.ledgerMapper = ledgerMapper;
        this.packageMapper = packageMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    public UserWallet getOrCreateWallet(Long userId, String tenantId) {
        Objects.requireNonNull(userId, "userId");
        walletMapper.createIfAbsent(userId, tenantId == null ? "DEFAULT" : tenantId);
        return walletMapper.selectOne(new LambdaQueryWrapper<UserWalletPO>()
                .eq(UserWalletPO::getUserId, userId)).toDomain();
    }

    @Override
    public boolean deductPoints(Long userId, long pointsToDeduct) {
        Objects.requireNonNull(userId, "userId");
        if (pointsToDeduct < 0) throw new IllegalArgumentException("Negative points");
        return pointsToDeduct == 0 || walletMapper.deductAvailable(userId, pointsToDeduct) == 1;
    }

    @Override
    public void addRechargePoints(Long userId, long pointsToAdd) {
        if (pointsToAdd <= 0) throw new IllegalArgumentException("Recharge points must be positive");
        getOrCreateWallet(userId, null);
        if (walletMapper.addRechargePoints(userId, pointsToAdd) != 1) {
            throw new IllegalStateException("Wallet credit failed");
        }
    }

    @Override
    public Optional<ModelPricing> getPricing(String modelName) {
        String targetModel = modelName != null ? modelName.toLowerCase() : "deepseek-chat";
        try {
            LambdaQueryWrapper<ModelPricingPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelPricingPO::getModelName, targetModel)
                    .eq(ModelPricingPO::getIsActive, true);
            ModelPricingPO po = pricingMapper.selectOne(wrapper);
            if (po != null) {
                return Optional.of(po.toDomain());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }

        return Optional.empty();
    }

    @Override
    public List<ModelPricing> getAllPricing() {
        try {
            LambdaQueryWrapper<ModelPricingPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelPricingPO::getIsActive, true);
            List<ModelPricingPO> pos = pricingMapper.selectList(wrapper);
            if (pos != null && !pos.isEmpty()) {
                return pos.stream().map(ModelPricingPO::toDomain).toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }

        return List.of();
    }

    @Override
    public void recordUsageLedger(TokenUsageLedger ledger) {
        if (ledger == null) return;
        if (ledger.getCreatedAt() == null) {
            ledger.setCreatedAt(LocalDateTime.now());
        }
        try {
            TokenUsageLedgerPO po = TokenUsageLedgerPO.fromDomain(ledger);
            if (ledgerMapper.insert(po) != 1) throw new IllegalStateException("Usage insert failed");
            log.info("[BILLING-DB] Token 消费流水入库: user={}, model={}, tokens={}, points={}",
                    ledger.getUserId(), ledger.getModel(), ledger.getTotalTokens(), ledger.getConsumedPoints());
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }
    }

    @Override
    public List<TokenUsageLedger> queryLedger(Long userId, int offset, int limit, String model, String taskType) {
        Long uid = Objects.requireNonNull(userId, "userId");
        try {
            LambdaQueryWrapper<TokenUsageLedgerPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TokenUsageLedgerPO::getUserId, uid);
            if (model != null && !model.isBlank()) {
                wrapper.eq(TokenUsageLedgerPO::getModel, model);
            }
            if (taskType != null && !taskType.isBlank()) {
                wrapper.eq(TokenUsageLedgerPO::getTaskType, taskType);
            }
            wrapper.orderByDesc(TokenUsageLedgerPO::getCreatedAt)
                    .last("LIMIT " + limit + " OFFSET " + offset);
            List<TokenUsageLedgerPO> pos = ledgerMapper.selectList(wrapper);
            if (pos != null) {
                return pos.stream().map(TokenUsageLedgerPO::toDomain).toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }

        return List.of();
    }

    @Override
    public long countLedger(Long userId, String model, String taskType) {
        Long uid = Objects.requireNonNull(userId, "userId");
        try {
            LambdaQueryWrapper<TokenUsageLedgerPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TokenUsageLedgerPO::getUserId, uid);
            if (model != null && !model.isBlank()) {
                wrapper.eq(TokenUsageLedgerPO::getModel, model);
            }
            if (taskType != null && !taskType.isBlank()) {
                wrapper.eq(TokenUsageLedgerPO::getTaskType, taskType);
            }
            return ledgerMapper.selectCount(wrapper);
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }
    }

    @Override
    public List<RechargePackage> listActivePackages() {
        try {
            LambdaQueryWrapper<RechargePackagePO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RechargePackagePO::getIsActive, true)
                    .orderByAsc(RechargePackagePO::getSortOrder);
            List<RechargePackagePO> pos = packageMapper.selectList(wrapper);
            if (pos != null && !pos.isEmpty()) {
                return pos.stream().map(RechargePackagePO::toDomain).toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }

        return List.of();
    }

    @Override
    public Optional<RechargePackage> getPackageById(Long packageId) {
        if (packageId == null) return Optional.empty();
        try {
            RechargePackagePO po = packageMapper.selectById(packageId);
            if (po != null) {
                return Boolean.TRUE.equals(po.getIsActive()) ? Optional.of(po.toDomain()) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }

        return Optional.empty();
    }

    @Override
    public void saveOrder(RechargeOrder order) {
        if (order == null) return;
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(LocalDateTime.now());
        }
        try {
            RechargeOrderPO po = RechargeOrderPO.fromDomain(order);
            if (orderMapper.insert(po) != 1) throw new IllegalStateException("Order insert failed");
            order.setId(po.getId());
            log.info("[BILLING-DB] 充值订单已持久化: orderNo={}, amount={}", order.getOrderNo(), order.getPayAmountCny());
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }
    }

    @Override
    public Optional<RechargeOrder> getOrderByNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return Optional.empty();
        try {
            LambdaQueryWrapper<RechargeOrderPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RechargeOrderPO::getOrderNo, orderNo);
            RechargeOrderPO po = orderMapper.selectOne(wrapper);
            if (po != null) {
                return Optional.of(po.toDomain());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }

        return Optional.empty();
    }

    @Override
    public void updateOrder(RechargeOrder order) {
        if (order == null || order.getOrderNo() == null) return;
        try {
            LambdaQueryWrapper<RechargeOrderPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RechargeOrderPO::getOrderNo, order.getOrderNo());
            RechargeOrderPO po = RechargeOrderPO.fromDomain(order);
            if (orderMapper.update(po, wrapper) != 1) throw new IllegalStateException("Order update failed");
            log.info("[BILLING-DB] 充值订单状态已更新: orderNo={}, status={}", order.getOrderNo(), order.getOrderStatus());
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }
    }

    @Override
    public List<UsageTrendPointDTO> getUsageTrend(Long userId, int days) {
        Long uid = Objects.requireNonNull(userId, "userId");
        int queryDays = days > 0 ? days : 7;
        LocalDateTime startDate = LocalDateTime.now().minusDays(queryDays);

        List<UsageTrendPointDTO> trendList = new ArrayList<>();
        try {
            List<Map<String, Object>> records = ledgerMapper.queryDailyTrend(uid, startDate);
            if (records != null && !records.isEmpty()) {
                for (Map<String, Object> r : records) {
                    trendList.add(UsageTrendPointDTO.builder()
                            .statDate(String.valueOf(r.get("stat_date")))
                            .totalTokens(((Number) r.get("total_tokens")).longValue())
                            .consumedPoints(((Number) r.get("consumed_points")).longValue())
                            .requestCount(((Number) r.get("request_count")).longValue())
                            .build());
                }
                return trendList;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Billing database operation failed", e);
        }

        // 智能补充近 N 天时序点（若无数据展示平滑 0 走势）
        LocalDate now = LocalDate.now();
        for (int i = queryDays - 1; i >= 0; i--) {
            LocalDate d = now.minusDays(i);
            String dateStr = d.toString();
            long tokens = 0, points = 0, count = 0;

            trendList.add(UsageTrendPointDTO.builder()
                    .statDate(dateStr)
                    .totalTokens(tokens)
                    .consumedPoints(points)
                    .requestCount(count)
                    .build());
        }

        return trendList;
    }

    @Override
    public Optional<RechargeOrder> getOrderByNoForUpdate(String orderNo) {
        return Optional.ofNullable(orderMapper.selectForUpdate(orderNo)).map(RechargeOrderPO::toDomain);
    }
}

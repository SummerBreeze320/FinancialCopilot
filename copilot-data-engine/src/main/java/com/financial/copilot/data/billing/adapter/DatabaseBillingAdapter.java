package com.financial.copilot.data.billing.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.data.billing.mapper.ModelPricingMapper;
import com.financial.copilot.data.billing.mapper.PointReservationMapper;
import com.financial.copilot.data.billing.mapper.RechargeOrderMapper;
import com.financial.copilot.data.billing.mapper.RechargePackageMapper;
import com.financial.copilot.data.billing.mapper.TokenUsageLedgerMapper;
import com.financial.copilot.data.billing.mapper.UserWalletMapper;
import com.financial.copilot.data.billing.po.ModelPricingPO;
import com.financial.copilot.data.billing.po.PointReservationPO;
import com.financial.copilot.data.billing.po.RechargeOrderPO;
import com.financial.copilot.data.billing.po.RechargePackagePO;
import com.financial.copilot.data.billing.po.TokenUsageLedgerPO;
import com.financial.copilot.data.billing.po.UserWalletPO;
import com.financial.copilot.domain.platform.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.platform.billing.entity.ModelPricing;
import com.financial.copilot.domain.platform.billing.entity.PointReservation;
import com.financial.copilot.domain.platform.billing.entity.RechargeOrder;
import com.financial.copilot.domain.platform.billing.entity.RechargePackage;
import com.financial.copilot.domain.platform.billing.entity.ReservationStatus;
import com.financial.copilot.domain.platform.billing.entity.TokenUsageLedger;
import com.financial.copilot.domain.platform.billing.entity.UserWallet;
import com.financial.copilot.domain.platform.billing.port.BillingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
@RequiredArgsConstructor
public class DatabaseBillingAdapter implements BillingPort {

    private final UserWalletMapper walletMapper;
    private final ModelPricingMapper pricingMapper;
    private final TokenUsageLedgerMapper ledgerMapper;
    private final RechargePackageMapper packageMapper;
    private final RechargeOrderMapper orderMapper;
    private final PointReservationMapper reservationMapper;

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

    @Override
    @Transactional("jdbcTransactionManager")
    public PointReservation reserve(Long userId, String workflowRunId, long estimatedPoints) {
        Long uid = Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        if (estimatedPoints <= 0) {
            throw new IllegalArgumentException("estimatedPoints must be positive");
        }

        getOrCreateWallet(uid, null);

        // 1. 检查是否已经存在预占记录 (保证幂等)
        PointReservationPO existing = reservationMapper.selectByWorkflowRunId(workflowRunId);
        if (existing != null) {
            return existing.toDomain();
        }

        // 2. 尝试原子冻结可用积分 (balance_points - frozen_points >= estimate)
        int frozenRows = walletMapper.freezePoints(uid, estimatedPoints);
        if (frozenRows == 0) {
            UserWalletPO wallet = walletMapper.selectOne(new LambdaQueryWrapper<UserWalletPO>()
                    .eq(UserWalletPO::getUserId, uid));
            long balance = wallet != null && wallet.getBalancePoints() != null ? wallet.getBalancePoints() : 0L;
            long frozen = wallet != null && wallet.getFrozenPoints() != null ? wallet.getFrozenPoints() : 0L;
            long available = Math.max(0L, balance - frozen);
            throw new WalletInsufficientException(uid, available, estimatedPoints);
        }

        // 3. 插入预占记录 (唯一索引冲突兜底)
        Instant now = Instant.now();
        int inserted = reservationMapper.insertIfAbsent(uid, workflowRunId, estimatedPoints, now, now);
        if (inserted == 0) {
            // 并发冲突说明已被另一个事务插入，回滚多冻结的积分并返回已存在的记录
            walletMapper.releaseFrozen(uid, estimatedPoints);
            PointReservationPO conflict = reservationMapper.selectByWorkflowRunId(workflowRunId);
            if (conflict != null) {
                return conflict.toDomain();
            }
        }

        PointReservationPO created = reservationMapper.selectByWorkflowRunId(workflowRunId);
        return created != null ? created.toDomain() : new PointReservation(
                null, uid, workflowRunId, estimatedPoints, 0L, ReservationStatus.ACTIVE, now, now);
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public PointReservation settle(Long userId, String workflowRunId, long actualPoints) {
        Long uid = Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        if (actualPoints < 0) {
            throw new IllegalArgumentException("actualPoints cannot be negative");
        }

        PointReservationPO existing = reservationMapper.selectByWorkflowRunId(workflowRunId);
        if (existing == null) {
            throw new IllegalStateException("Reservation not found for workflowRunId: " + workflowRunId);
        }

        // 幂等：若已是非 ACTIVE 状态（已结算或已释放），直接返回，不重复扣费
        if (!"ACTIVE".equalsIgnoreCase(existing.getStatus())) {
            return existing.toDomain();
        }

        Instant now = Instant.now();
        int settledRows = reservationMapper.settleIfActive(uid, workflowRunId, actualPoints, now);
        if (settledRows == 1) {
            // 原子扣减实际消费积分，并解冻预占冻结额度
            walletMapper.settleReservation(uid, actualPoints, existing.getEstimatedPoints());
            log.info("[BILLING-DB] 算力预占结算完成: user={}, runId={}, estimated={}, actual={}",
                    uid, workflowRunId, existing.getEstimatedPoints(), actualPoints);
        }

        PointReservationPO updated = reservationMapper.selectByWorkflowRunId(workflowRunId);
        return updated != null ? updated.toDomain() : existing.toDomain();
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public void release(Long userId, String workflowRunId) {
        Long uid = Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workflowRunId, "workflowRunId");

        PointReservationPO existing = reservationMapper.selectByWorkflowRunId(workflowRunId);
        if (existing == null || !"ACTIVE".equalsIgnoreCase(existing.getStatus())) {
            return; // 幂等放行
        }

        Instant now = Instant.now();
        int releasedRows = reservationMapper.releaseIfActive(uid, workflowRunId, now);
        if (releasedRows == 1) {
            walletMapper.releaseFrozen(uid, existing.getEstimatedPoints());
            log.info("[BILLING-DB] 算力预占释放解冻: user={}, runId={}, releasedPoints={}",
                    uid, workflowRunId, existing.getEstimatedPoints());
        }
    }

    @Override
    public Optional<PointReservation> getReservation(Long userId, String workflowRunId) {
        if (workflowRunId == null || workflowRunId.isBlank()) {
            return Optional.empty();
        }
        PointReservationPO po = reservationMapper.selectByWorkflowRunId(workflowRunId);
        if (po != null && (userId == null || userId.equals(po.getUserId()))) {
            return Optional.of(po.toDomain());
        }
        return Optional.empty();
    }
}

package com.financial.copilot.agent.core.platform.billing;

import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.domain.platform.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.platform.billing.entity.ModelPricing;
import com.financial.copilot.domain.platform.billing.entity.PointReservation;
import com.financial.copilot.domain.platform.billing.entity.RechargeOrder;
import com.financial.copilot.domain.platform.billing.entity.RechargePackage;
import com.financial.copilot.domain.platform.billing.entity.ReservationStatus;
import com.financial.copilot.domain.platform.billing.entity.TokenUsageLedger;
import com.financial.copilot.domain.platform.billing.entity.UserWallet;
import com.financial.copilot.domain.platform.billing.port.BillingPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h1>内存化算力计费与钱包端口测试桩 (In-Memory Billing Port Test Double)</h1>
 * <p>
 * 提供轻量级无外部数据库依赖的钱包、预占与流水内存测试实现，具备线程安全的原子预占与结算语义。
 * </p>
 *
 * @author FinancialCopilot
 */
public class InMemoryBillingPort implements BillingPort {

    private final Map<Long, UserWallet> wallets = new ConcurrentHashMap<>();
    private final Map<String, PointReservation> reservations = new ConcurrentHashMap<>();
    private final Map<String, ModelPricing> pricings = new ConcurrentHashMap<>();
    private final List<TokenUsageLedger> ledgers = new ArrayList<>();
    private final Map<Long, RechargePackage> packages = new ConcurrentHashMap<>();
    private final Map<String, RechargeOrder> orders = new ConcurrentHashMap<>();
    private final AtomicLong reservationIdGen = new AtomicLong(1);

    public InMemoryBillingPort() {
        pricings.put("deepseek-chat", ModelPricing.builder()
                .modelName("deepseek-chat")
                .providerType("DEEPSEEK")
                .inputPricePerK(new BigDecimal("10.0"))
                .outputPricePerK(new BigDecimal("20.0"))
                .isActive(true)
                .build());
    }

    @Override
    public synchronized UserWallet getOrCreateWallet(Long userId, String tenantId) {
        return wallets.computeIfAbsent(userId, uid -> UserWallet.builder()
                .id(uid)
                .userId(uid)
                .tenantId(tenantId != null ? tenantId : "DEFAULT")
                .balancePoints(0L)
                .frozenPoints(0L)
                .totalRechargedPoints(0L)
                .totalConsumedPoints(0L)
                .walletStatus("NORMAL")
                .version(0L)
                .updatedAt(LocalDateTime.now())
                .build());
    }

    @Override
    public synchronized boolean deductPoints(Long userId, long pointsToDeduct) {
        UserWallet wallet = getOrCreateWallet(userId, null);
        long available = wallet.getBalancePoints() - wallet.getFrozenPoints();
        if (available < pointsToDeduct) {
            return false;
        }
        wallet.setBalancePoints(wallet.getBalancePoints() - pointsToDeduct);
        wallet.setTotalConsumedPoints(wallet.getTotalConsumedPoints() + pointsToDeduct);
        wallet.setVersion(wallet.getVersion() + 1);
        wallet.setUpdatedAt(LocalDateTime.now());
        return true;
    }

    @Override
    public synchronized void addRechargePoints(Long userId, long pointsToAdd) {
        UserWallet wallet = getOrCreateWallet(userId, null);
        wallet.setBalancePoints(wallet.getBalancePoints() + pointsToAdd);
        wallet.setTotalRechargedPoints(wallet.getTotalRechargedPoints() + pointsToAdd);
        wallet.setVersion(wallet.getVersion() + 1);
        wallet.setUpdatedAt(LocalDateTime.now());
    }

    @Override
    public Optional<ModelPricing> getPricing(String modelName) {
        return Optional.ofNullable(pricings.get(modelName != null ? modelName.toLowerCase() : "deepseek-chat"));
    }

    @Override
    public List<ModelPricing> getAllPricing() {
        return new ArrayList<>(pricings.values());
    }

    @Override
    public synchronized void recordUsageLedger(TokenUsageLedger ledger) {
        ledgers.add(ledger);
    }

    @Override
    public synchronized List<TokenUsageLedger> queryLedger(Long userId, int offset, int limit, String model, String taskType) {
        return ledgers.stream()
                .filter(l -> l.getUserId().equals(userId))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized long countLedger(Long userId, String model, String taskType) {
        return ledgers.stream()
                .filter(l -> l.getUserId().equals(userId))
                .count();
    }

    @Override
    public List<RechargePackage> listActivePackages() {
        return new ArrayList<>(packages.values());
    }

    @Override
    public Optional<RechargePackage> getPackageById(Long packageId) {
        return Optional.ofNullable(packages.get(packageId));
    }

    @Override
    public synchronized void saveOrder(RechargeOrder order) {
        orders.put(order.getOrderNo(), order);
    }

    @Override
    public Optional<RechargeOrder> getOrderByNo(String orderNo) {
        return Optional.ofNullable(orders.get(orderNo));
    }

    @Override
    public Optional<RechargeOrder> getOrderByNoForUpdate(String orderNo) {
        return getOrderByNo(orderNo);
    }

    @Override
    public synchronized void updateOrder(RechargeOrder order) {
        orders.put(order.getOrderNo(), order);
    }

    @Override
    public List<UsageTrendPointDTO> getUsageTrend(Long userId, int days) {
        return List.of();
    }

    @Override
    public synchronized PointReservation reserve(Long userId, String workflowRunId, long estimatedPoints) {
        PointReservation existing = reservations.get(workflowRunId);
        if (existing != null) {
            return existing;
        }

        UserWallet wallet = getOrCreateWallet(userId, null);
        long available = wallet.getBalancePoints() - wallet.getFrozenPoints();
        if (available < estimatedPoints) {
            throw new WalletInsufficientException(userId, available, estimatedPoints);
        }

        wallet.setFrozenPoints(wallet.getFrozenPoints() + estimatedPoints);
        wallet.setVersion(wallet.getVersion() + 1);
        wallet.setUpdatedAt(LocalDateTime.now());

        PointReservation reservation = new PointReservation(
                reservationIdGen.getAndIncrement(),
                userId,
                workflowRunId,
                estimatedPoints,
                0L,
                ReservationStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        );
        reservations.put(workflowRunId, reservation);
        return reservation;
    }

    @Override
    public synchronized PointReservation settle(Long userId, String workflowRunId, long actualPoints) {
        PointReservation existing = reservations.get(workflowRunId);
        if (existing == null) {
            throw new IllegalStateException("Reservation not found: " + workflowRunId);
        }
        if (existing.status() != ReservationStatus.ACTIVE) {
            return existing; // 幂等放行，防止重复结算重复扣减
        }

        UserWallet wallet = getOrCreateWallet(userId, null);
        wallet.setBalancePoints(wallet.getBalancePoints() - actualPoints);
        wallet.setFrozenPoints(Math.max(0L, wallet.getFrozenPoints() - existing.estimatedPoints()));
        wallet.setTotalConsumedPoints(wallet.getTotalConsumedPoints() + actualPoints);
        wallet.setVersion(wallet.getVersion() + 1);
        wallet.setUpdatedAt(LocalDateTime.now());

        PointReservation settled = new PointReservation(
                existing.id(),
                userId,
                workflowRunId,
                existing.estimatedPoints(),
                actualPoints,
                ReservationStatus.SETTLED,
                existing.createdAt(),
                Instant.now()
        );
        reservations.put(workflowRunId, settled);
        return settled;
    }

    @Override
    public synchronized void release(Long userId, String workflowRunId) {
        PointReservation existing = reservations.get(workflowRunId);
        if (existing == null || existing.status() != ReservationStatus.ACTIVE) {
            return; // 幂等放行
        }

        UserWallet wallet = getOrCreateWallet(userId, null);
        wallet.setFrozenPoints(Math.max(0L, wallet.getFrozenPoints() - existing.estimatedPoints()));
        wallet.setVersion(wallet.getVersion() + 1);
        wallet.setUpdatedAt(LocalDateTime.now());

        PointReservation released = new PointReservation(
                existing.id(),
                userId,
                workflowRunId,
                existing.estimatedPoints(),
                0L,
                ReservationStatus.RELEASED,
                existing.createdAt(),
                Instant.now()
        );
        reservations.put(workflowRunId, released);
    }

    @Override
    public Optional<PointReservation> getReservation(Long userId, String workflowRunId) {
        return Optional.ofNullable(reservations.get(workflowRunId));
    }
}

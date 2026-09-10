package com.financial.copilot.data.billing.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.copilot.data.billing.mapper.*;
import com.financial.copilot.data.billing.po.*;
import com.financial.copilot.domain.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.billing.entity.*;
import com.financial.copilot.domain.billing.port.BillingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>基于 MyBatis-Plus 与 PostgreSQL 的商业化计量计费数据适配器</h1>
 * <p>
 * 实现领域层 {@link BillingPort} SPI 契约。
 * 提供原子乐观锁防超扣、流水不可篡改落库、阶梯定价解析、套餐与订单状态流转，
 * 并支持无数据库单测环境下的安全内存降级兜底。
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

    // 内存兜底存储（针对无数据库本地环境或单测隔离）
    private final Map<Long, UserWallet> memoryWallets = new ConcurrentHashMap<>();
    private final List<TokenUsageLedger> memoryLedgers = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, RechargeOrder> memoryOrders = new ConcurrentHashMap<>();

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
        Long uid = userId != null ? userId : 1L;
        try {
            LambdaQueryWrapper<UserWalletPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserWalletPO::getUserId, uid);
            UserWalletPO po = walletMapper.selectOne(wrapper);
            if (po != null) {
                return po.toDomain();
            }

            // 新用户默认赠送 100,000 体验点数 (价值 10 元)
            UserWalletPO newPo = UserWalletPO.builder()
                    .userId(uid)
                    .tenantId(tenantId != null ? tenantId : "DEFAULT")
                    .balancePoints(100000L)
                    .frozenPoints(0L)
                    .totalRechargedPoints(100000L)
                    .totalConsumedPoints(0L)
                    .walletStatus("NORMAL")
                    .version(0L)
                    .updatedAt(LocalDateTime.now())
                    .build();
            walletMapper.insert(newPo);
            log.info("[BILLING-DB] 初始化新用户体验算力钱包成功: userId={}, points=100000", uid);
            return newPo.toDomain();
        } catch (Exception e) {
            log.warn("[BILLING-DB] 数据库不可用，启用内存钱包适配: uid={}, error={}", uid, e.getMessage());
            return memoryWallets.computeIfAbsent(uid, k -> UserWallet.builder()
                    .id(k)
                    .userId(k)
                    .tenantId(tenantId != null ? tenantId : "DEFAULT")
                    .balancePoints(100000L)
                    .frozenPoints(0L)
                    .totalRechargedPoints(100000L)
                    .totalConsumedPoints(0L)
                    .walletStatus("NORMAL")
                    .version(0L)
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
    }

    @Override
    public boolean deductPoints(Long userId, long pointsToDeduct) {
        if (pointsToDeduct <= 0) {
            return true;
        }
        Long uid = userId != null ? userId : 1L;
        try {
            LambdaQueryWrapper<UserWalletPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserWalletPO::getUserId, uid);
            UserWalletPO po = walletMapper.selectOne(wrapper);
            if (po == null || po.getBalancePoints() < pointsToDeduct) {
                log.warn("[BILLING-DB] 扣减失败：用户钱包不存在或余额不足: userId={}, balance={}, required={}",
                        uid, po != null ? po.getBalancePoints() : 0, pointsToDeduct);
                return false;
            }

            int rows = walletMapper.deductWithLock(uid, pointsToDeduct, po.getVersion());
            if (rows > 0) {
                log.info("[BILLING-DB] 乐观锁扣减算力点数成功: userId={}, points={}, remaining={}",
                        uid, pointsToDeduct, po.getBalancePoints() - pointsToDeduct);
                return true;
            } else {
                log.warn("[BILLING-DB] 并发版本冲突，乐观锁扣减失败: userId={}", uid);
                return false;
            }
        } catch (Exception e) {
            log.warn("[BILLING-DB] 数据库扣减异常，采用内存钱包扣减兜底: error={}", e.getMessage());
            UserWallet wallet = getOrCreateWallet(uid, null);
            synchronized (wallet) {
                if (wallet.getBalancePoints() < pointsToDeduct) {
                    return false;
                }
                wallet.setBalancePoints(wallet.getBalancePoints() - pointsToDeduct);
                wallet.setTotalConsumedPoints(wallet.getTotalConsumedPoints() + pointsToDeduct);
                wallet.setVersion(wallet.getVersion() + 1);
                wallet.setUpdatedAt(LocalDateTime.now());
                return true;
            }
        }
    }

    @Override
    public void addRechargePoints(Long userId, long pointsToAdd) {
        if (pointsToAdd <= 0) return;
        Long uid = userId != null ? userId : 1L;
        try {
            int rows = walletMapper.addRechargePoints(uid, pointsToAdd);
            if (rows == 0) {
                getOrCreateWallet(uid, null);
                walletMapper.addRechargePoints(uid, pointsToAdd);
            }
            log.info("[BILLING-DB] 充值点数到账成功: userId={}, points={}", uid, pointsToAdd);
        } catch (Exception e) {
            log.warn("[BILLING-DB] 数据库充值异常，启用内存钱包入账: error={}", e.getMessage());
            UserWallet wallet = getOrCreateWallet(uid, null);
            synchronized (wallet) {
                wallet.setBalancePoints(wallet.getBalancePoints() + pointsToAdd);
                wallet.setTotalRechargedPoints(wallet.getTotalRechargedPoints() + pointsToAdd);
                wallet.setVersion(wallet.getVersion() + 1);
                wallet.setUpdatedAt(LocalDateTime.now());
            }
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
            log.warn("[BILLING-DB] 查询模型定价异常，启用预置阶梯矩阵: error={}", e.getMessage());
        }

        return Optional.of(resolveDefaultPricing(targetModel));
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
            log.warn("[BILLING-DB] 查询定价列表异常，返回内置默认定价矩阵: error={}", e.getMessage());
        }

        return List.of(
                resolveDefaultPricing("deepseek-chat"),
                resolveDefaultPricing("deepseek-reasoner"),
                resolveDefaultPricing("gpt-4o-mini"),
                resolveDefaultPricing("gpt-4o"),
                resolveDefaultPricing("o1"),
                resolveDefaultPricing("qwen-plus")
        );
    }

    @Override
    public void recordUsageLedger(TokenUsageLedger ledger) {
        if (ledger == null) return;
        if (ledger.getCreatedAt() == null) {
            ledger.setCreatedAt(LocalDateTime.now());
        }
        try {
            TokenUsageLedgerPO po = TokenUsageLedgerPO.fromDomain(ledger);
            ledgerMapper.insert(po);
            log.info("[BILLING-DB] Token 消费流水入库: user={}, model={}, tokens={}, points={}",
                    ledger.getUserId(), ledger.getModel(), ledger.getTotalTokens(), ledger.getConsumedPoints());
        } catch (Exception e) {
            log.warn("[BILLING-DB] 消费流水写入异常，存入内存对账池: error={}", e.getMessage());
            memoryLedgers.add(ledger);
        }
    }

    @Override
    public List<TokenUsageLedger> queryLedger(Long userId, int offset, int limit, String model, String taskType) {
        Long uid = userId != null ? userId : 1L;
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
            log.warn("[BILLING-DB] 查询消费流水异常，从内存获取: error={}", e.getMessage());
        }

        return memoryLedgers.stream()
                .filter(l -> Objects.equals(l.getUserId(), uid))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countLedger(Long userId, String model, String taskType) {
        Long uid = userId != null ? userId : 1L;
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
            return memoryLedgers.stream()
                    .filter(l -> Objects.equals(l.getUserId(), uid))
                    .count();
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
            log.warn("[BILLING-DB] 查询套餐异常，使用默认内置四阶梯规格: error={}", e.getMessage());
        }

        return getDefaultPackages();
    }

    @Override
    public Optional<RechargePackage> getPackageById(Long packageId) {
        if (packageId == null) return Optional.empty();
        try {
            RechargePackagePO po = packageMapper.selectById(packageId);
            if (po != null) {
                return Optional.of(po.toDomain());
            }
        } catch (Exception e) {
            log.warn("[BILLING-DB] 查询套餐详情异常: error={}", e.getMessage());
        }

        return getDefaultPackages().stream().filter(p -> p.getId().equals(packageId)).findFirst();
    }

    @Override
    public void saveOrder(RechargeOrder order) {
        if (order == null) return;
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(LocalDateTime.now());
        }
        try {
            RechargeOrderPO po = RechargeOrderPO.fromDomain(order);
            orderMapper.insert(po);
            order.setId(po.getId());
            log.info("[BILLING-DB] 充值订单已持久化: orderNo={}, amount={}", order.getOrderNo(), order.getPayAmountCny());
        } catch (Exception e) {
            log.warn("[BILLING-DB] 订单保存数据库异常，存入内存: error={}", e.getMessage());
            memoryOrders.put(order.getOrderNo(), order);
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
            log.warn("[BILLING-DB] 查询订单异常，从内存检索: orderNo={}", orderNo);
        }

        return Optional.ofNullable(memoryOrders.get(orderNo));
    }

    @Override
    public void updateOrder(RechargeOrder order) {
        if (order == null || order.getOrderNo() == null) return;
        try {
            LambdaQueryWrapper<RechargeOrderPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RechargeOrderPO::getOrderNo, order.getOrderNo());
            RechargeOrderPO po = RechargeOrderPO.fromDomain(order);
            orderMapper.update(po, wrapper);
            log.info("[BILLING-DB] 充值订单状态已更新: orderNo={}, status={}", order.getOrderNo(), order.getOrderStatus());
        } catch (Exception e) {
            log.warn("[BILLING-DB] 订单状态更新异常，更新内存: error={}", e.getMessage());
            memoryOrders.put(order.getOrderNo(), order);
        }
    }

    @Override
    public List<UsageTrendPointDTO> getUsageTrend(Long userId, int days) {
        Long uid = userId != null ? userId : 1L;
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
            log.warn("[BILLING-DB] 查询走势时序数据异常，自动生成连续近序时序: error={}", e.getMessage());
        }

        // 智能补充近 N 天时序点（若无数据展示平滑 0 走势）
        LocalDate now = LocalDate.now();
        for (int i = queryDays - 1; i >= 0; i--) {
            LocalDate d = now.minusDays(i);
            String dateStr = d.toString();
            long tokens = memoryLedgers.stream()
                    .filter(l -> Objects.equals(l.getUserId(), uid) && l.getCreatedAt() != null && l.getCreatedAt().toLocalDate().equals(d))
                    .mapToLong(l -> l.getTotalTokens() != null ? l.getTotalTokens() : 0)
                    .sum();
            long points = memoryLedgers.stream()
                    .filter(l -> Objects.equals(l.getUserId(), uid) && l.getCreatedAt() != null && l.getCreatedAt().toLocalDate().equals(d))
                    .mapToLong(l -> l.getConsumedPoints() != null ? l.getConsumedPoints() : 0)
                    .sum();
            long count = memoryLedgers.stream()
                    .filter(l -> Objects.equals(l.getUserId(), uid) && l.getCreatedAt() != null && l.getCreatedAt().toLocalDate().equals(d))
                    .count();

            trendList.add(UsageTrendPointDTO.builder()
                    .statDate(dateStr)
                    .totalTokens(tokens)
                    .consumedPoints(points)
                    .requestCount(count)
                    .build());
        }

        return trendList;
    }

    private ModelPricing resolveDefaultPricing(String model) {
        return switch (model.toLowerCase()) {
            case "deepseek-reasoner" -> ModelPricing.builder()
                    .id(2L).providerType("DEEPSEEK").modelName("deepseek-reasoner")
                    .inputPricePerK(new BigDecimal("40.0")).outputPricePerK(new BigDecimal("160.0"))
                    .cacheHitPricePerK(new BigDecimal("10.0")).isActive(true).build();
            case "gpt-4o" -> ModelPricing.builder()
                    .id(3L).providerType("OPENAI").modelName("gpt-4o")
                    .inputPricePerK(new BigDecimal("250.0")).outputPricePerK(new BigDecimal("1000.0"))
                    .cacheHitPricePerK(new BigDecimal("125.0")).isActive(true).build();
            case "gpt-4o-mini" -> ModelPricing.builder()
                    .id(4L).providerType("OPENAI").modelName("gpt-4o-mini")
                    .inputPricePerK(new BigDecimal("15.0")).outputPricePerK(new BigDecimal("60.0"))
                    .cacheHitPricePerK(new BigDecimal("7.5")).isActive(true).build();
            case "o1" -> ModelPricing.builder()
                    .id(5L).providerType("OPENAI").modelName("o1")
                    .inputPricePerK(new BigDecimal("300.0")).outputPricePerK(new BigDecimal("1200.0"))
                    .cacheHitPricePerK(new BigDecimal("150.0")).isActive(true).build();
            case "qwen-plus" -> ModelPricing.builder()
                    .id(6L).providerType("QWEN").modelName("qwen-plus")
                    .inputPricePerK(new BigDecimal("8.0")).outputPricePerK(new BigDecimal("20.0"))
                    .cacheHitPricePerK(new BigDecimal("2.0")).isActive(true).build();
            default -> ModelPricing.builder()
                    .id(1L).providerType("DEEPSEEK").modelName("deepseek-chat")
                    .inputPricePerK(new BigDecimal("10.0")).outputPricePerK(new BigDecimal("20.0"))
                    .cacheHitPricePerK(new BigDecimal("2.0")).isActive(true).build();
        };
    }

    private List<RechargePackage> getDefaultPackages() {
        return List.of(
                RechargePackage.builder()
                        .id(1L)
                        .packageName("投研尝鲜包")
                        .priceCny(new BigDecimal("49.00"))
                        .grantedPoints(500000L) // 50万点
                        .bonusPoints(0L)
                        .badge("入门推荐")
                        .sortOrder(1)
                        .isActive(true)
                        .build(),
                RechargePackage.builder()
                        .id(2L)
                        .packageName("专业分析师包")
                        .priceCny(new BigDecimal("199.00"))
                        .grantedPoints(2000000L) // 200万点
                        .bonusPoints(200000L) // 送20万点 (10%)
                        .badge("热销首选")
                        .sortOrder(2)
                        .isActive(true)
                        .build(),
                RechargePackage.builder()
                        .id(3L)
                        .packageName("机构进阶包")
                        .priceCny(new BigDecimal("599.00"))
                        .grantedPoints(6000000L) // 600万点
                        .bonusPoints(1200000L) // 送120万点 (20%)
                        .badge("送20%加赠")
                        .sortOrder(3)
                        .isActive(true)
                        .build(),
                RechargePackage.builder()
                        .id(4L)
                        .packageName("企业旗舰包")
                        .priceCny(new BigDecimal("2999.00"))
                        .grantedPoints(30000000L) // 3000万点
                        .bonusPoints(9000000L) // 送900万点 (30%)
                        .badge("尊享 1V1 投研支持")
                        .sortOrder(4)
                        .isActive(true)
                        .build()
        );
    }
}

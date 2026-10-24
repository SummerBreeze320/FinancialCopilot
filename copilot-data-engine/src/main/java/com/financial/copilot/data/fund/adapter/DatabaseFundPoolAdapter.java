package com.financial.copilot.data.fund.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.copilot.data.fund.mapper.FundPoolMapper;
import com.financial.copilot.data.fund.po.FundPoolPO;
import com.financial.copilot.domain.business.fund.entity.FundPoolItem;
import com.financial.copilot.domain.business.fund.port.FundPoolPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * <h1>基金池持久化适配器 (DatabaseFundPoolAdapter)</h1>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DatabaseFundPoolAdapter implements FundPoolPort {

    private final FundPoolMapper mapper;

    @Override
    public FundPoolItem addOrUpdate(FundPoolItem item) {
        if (item == null || item.getUserId() == null || item.getFundCode() == null) {
            throw new IllegalArgumentException("userId and fundCode must not be null");
        }

        String poolType = item.getPoolType() != null ? item.getPoolType() : "CORE";

        LambdaQueryWrapper<FundPoolPO> query = new LambdaQueryWrapper<FundPoolPO>()
                .eq(FundPoolPO::getUserId, item.getUserId())
                .eq(FundPoolPO::getFundCode, item.getFundCode())
                .eq(FundPoolPO::getPoolType, poolType);

        FundPoolPO existing = mapper.selectOne(query);
        LocalDateTime now = LocalDateTime.now();

        if (existing != null) {
            existing.setFundName(item.getFundName());
            existing.setTargetWeight(item.getTargetWeight());
            existing.setReason(item.getReason());
            existing.setTags(item.getTags());
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
            item.setId(existing.getId());
        } else {
            FundPoolPO po = FundPoolPO.builder()
                    .userId(item.getUserId())
                    .fundCode(item.getFundCode())
                    .fundName(item.getFundName())
                    .poolType(poolType)
                    .targetWeight(item.getTargetWeight())
                    .reason(item.getReason())
                    .tags(item.getTags())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            mapper.insert(po);
            item.setId(po.getId());
        }

        log.info("Saved fund pool item: userId={}, fundCode={}, poolType={}",
                item.getUserId(), item.getFundCode(), poolType);
        return item;
    }

    @Override
    public boolean remove(Long userId, String fundCode, String poolType) {
        if (userId == null || fundCode == null) return false;
        LambdaQueryWrapper<FundPoolPO> query = new LambdaQueryWrapper<FundPoolPO>()
                .eq(FundPoolPO::getUserId, userId)
                .eq(FundPoolPO::getFundCode, fundCode);
        if (poolType != null && !poolType.isBlank()) {
            query.eq(FundPoolPO::getPoolType, poolType);
        }
        int count = mapper.delete(query);
        log.info("Removed fund pool item: userId={}, fundCode={}, poolType={}, deletedCount={}",
                userId, fundCode, poolType, count);
        return count > 0;
    }

    @Override
    public int clear(Long userId, String poolType) {
        if (userId == null) return 0;
        LambdaQueryWrapper<FundPoolPO> query = new LambdaQueryWrapper<FundPoolPO>()
                .eq(FundPoolPO::getUserId, userId);
        if (poolType != null && !poolType.isBlank()) {
            query.eq(FundPoolPO::getPoolType, poolType);
        }
        int count = mapper.delete(query);
        log.warn("Cleared fund pool: userId={}, poolType={}, clearedCount={}", userId, poolType, count);
        return count;
    }

    @Override
    public Optional<FundPoolItem> findByUserAndCode(Long userId, String fundCode, String poolType) {
        if (userId == null || fundCode == null) return Optional.empty();
        LambdaQueryWrapper<FundPoolPO> query = new LambdaQueryWrapper<FundPoolPO>()
                .eq(FundPoolPO::getUserId, userId)
                .eq(FundPoolPO::getFundCode, fundCode);
        if (poolType != null && !poolType.isBlank()) {
            query.eq(FundPoolPO::getPoolType, poolType);
        }
        FundPoolPO po = mapper.selectOne(query);
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public List<FundPoolItem> listByUser(Long userId, String poolType) {
        if (userId == null) return List.of();
        LambdaQueryWrapper<FundPoolPO> query = new LambdaQueryWrapper<FundPoolPO>()
                .eq(FundPoolPO::getUserId, userId)
                .orderByDesc(FundPoolPO::getUpdatedAt);
        if (poolType != null && !poolType.isBlank()) {
            query.eq(FundPoolPO::getPoolType, poolType);
        }
        return mapper.selectList(query).stream().map(this::toDomain).toList();
    }

    private FundPoolItem toDomain(FundPoolPO po) {
        return FundPoolItem.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .fundCode(po.getFundCode())
                .fundName(po.getFundName())
                .poolType(po.getPoolType())
                .targetWeight(po.getTargetWeight())
                .reason(po.getReason())
                .tags(po.getTags())
                .createdAt(po.getCreatedAt() != null ? po.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant() : null)
                .updatedAt(po.getUpdatedAt() != null ? po.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant() : null)
                .build();
    }
}

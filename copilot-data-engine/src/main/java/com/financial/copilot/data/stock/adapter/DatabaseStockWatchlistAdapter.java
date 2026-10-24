package com.financial.copilot.data.stock.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.copilot.data.stock.mapper.StockWatchlistMapper;
import com.financial.copilot.data.stock.po.StockWatchlistPO;
import com.financial.copilot.domain.business.stock.entity.StockWatchlistItem;
import com.financial.copilot.domain.business.stock.port.StockWatchlistPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * <h1>自选股持久化适配器 (DatabaseStockWatchlistAdapter)</h1>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DatabaseStockWatchlistAdapter implements StockWatchlistPort {

    private final StockWatchlistMapper mapper;

    @Override
    public StockWatchlistItem addOrUpdate(StockWatchlistItem item) {
        if (item == null || item.getUserId() == null || item.getStockCode() == null) {
            throw new IllegalArgumentException("userId and stockCode must not be null");
        }

        String groupName = item.getGroupName() != null && !item.getGroupName().isBlank()
                ? item.getGroupName() : "默认";

        LambdaQueryWrapper<StockWatchlistPO> query = new LambdaQueryWrapper<StockWatchlistPO>()
                .eq(StockWatchlistPO::getUserId, item.getUserId())
                .eq(StockWatchlistPO::getStockCode, item.getStockCode())
                .eq(StockWatchlistPO::getGroupName, groupName);

        StockWatchlistPO existing = mapper.selectOne(query);
        LocalDateTime now = LocalDateTime.now();

        if (existing != null) {
            existing.setStockName(item.getStockName());
            existing.setTargetPrice(item.getTargetPrice());
            existing.setNotes(item.getNotes());
            existing.setPriority(item.getPriority());
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
            item.setId(existing.getId());
        } else {
            StockWatchlistPO po = StockWatchlistPO.builder()
                    .userId(item.getUserId())
                    .stockCode(item.getStockCode())
                    .stockName(item.getStockName())
                    .groupName(groupName)
                    .targetPrice(item.getTargetPrice())
                    .notes(item.getNotes())
                    .priority(item.getPriority() != null ? item.getPriority() : "MEDIUM")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            mapper.insert(po);
            item.setId(po.getId());
        }

        log.info("Saved stock watchlist item: userId={}, stockCode={}, groupName={}",
                item.getUserId(), item.getStockCode(), groupName);
        return item;
    }

    @Override
    public boolean remove(Long userId, String stockCode, String groupName) {
        if (userId == null || stockCode == null) return false;
        LambdaQueryWrapper<StockWatchlistPO> query = new LambdaQueryWrapper<StockWatchlistPO>()
                .eq(StockWatchlistPO::getUserId, userId)
                .eq(StockWatchlistPO::getStockCode, stockCode);
        if (groupName != null && !groupName.isBlank()) {
            query.eq(StockWatchlistPO::getGroupName, groupName);
        }
        int count = mapper.delete(query);
        log.info("Removed stock watchlist item: userId={}, stockCode={}, groupName={}, count={}",
                userId, stockCode, groupName, count);
        return count > 0;
    }

    @Override
    public int clear(Long userId, String groupName) {
        if (userId == null) return 0;
        LambdaQueryWrapper<StockWatchlistPO> query = new LambdaQueryWrapper<StockWatchlistPO>()
                .eq(StockWatchlistPO::getUserId, userId);
        if (groupName != null && !groupName.isBlank()) {
            query.eq(StockWatchlistPO::getGroupName, groupName);
        }
        int count = mapper.delete(query);
        log.warn("Cleared stock watchlist: userId={}, groupName={}, count={}", userId, groupName, count);
        return count;
    }

    @Override
    public Optional<StockWatchlistItem> findByUserAndCode(Long userId, String stockCode, String groupName) {
        if (userId == null || stockCode == null) return Optional.empty();
        LambdaQueryWrapper<StockWatchlistPO> query = new LambdaQueryWrapper<StockWatchlistPO>()
                .eq(StockWatchlistPO::getUserId, userId)
                .eq(StockWatchlistPO::getStockCode, stockCode);
        if (groupName != null && !groupName.isBlank()) {
            query.eq(StockWatchlistPO::getGroupName, groupName);
        }
        StockWatchlistPO po = mapper.selectOne(query);
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public List<StockWatchlistItem> listByUser(Long userId, String groupName) {
        if (userId == null) return List.of();
        LambdaQueryWrapper<StockWatchlistPO> query = new LambdaQueryWrapper<StockWatchlistPO>()
                .eq(StockWatchlistPO::getUserId, userId)
                .orderByDesc(StockWatchlistPO::getUpdatedAt);
        if (groupName != null && !groupName.isBlank()) {
            query.eq(StockWatchlistPO::getGroupName, groupName);
        }
        return mapper.selectList(query).stream().map(this::toDomain).toList();
    }

    private StockWatchlistItem toDomain(StockWatchlistPO po) {
        return StockWatchlistItem.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .stockCode(po.getStockCode())
                .stockName(po.getStockName())
                .groupName(po.getGroupName())
                .targetPrice(po.getTargetPrice())
                .notes(po.getNotes())
                .priority(po.getPriority())
                .createdAt(po.getCreatedAt() != null ? po.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant() : null)
                .updatedAt(po.getUpdatedAt() != null ? po.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant() : null)
                .build();
    }
}

package com.financial.copilot.data.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.billing.po.TokenUsageLedgerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <h1>Token 消费对账明细持久层 Mapper (MyBatis-Plus)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface TokenUsageLedgerMapper extends BaseMapper<TokenUsageLedgerPO> {

    /**
     * 按日时序聚合统计用户的 Token 消耗与点数走势
     *
     * @param userId    用户 ID
     * @param startDate 起始统计时间
     * @return 每日聚合点数据映射列表
     */
    @Select("SELECT TO_CHAR(created_at, 'YYYY-MM-DD') AS stat_date, " +
            "       COALESCE(SUM(total_tokens), 0) AS total_tokens, " +
            "       COALESCE(SUM(consumed_points), 0) AS consumed_points, " +
            "       COUNT(id) AS request_count " +
            "FROM llm_token_usage_ledger " +
            "WHERE user_id = #{userId} AND created_at >= #{startDate} " +
            "GROUP BY TO_CHAR(created_at, 'YYYY-MM-DD') " +
            "ORDER BY stat_date ASC")
    List<Map<String, Object>> queryDailyTrend(@Param("userId") Long userId,
                                              @Param("startDate") LocalDateTime startDate);
}

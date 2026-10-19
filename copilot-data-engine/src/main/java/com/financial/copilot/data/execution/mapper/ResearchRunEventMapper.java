package com.financial.copilot.data.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.execution.po.ResearchRunEventPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * <h1>运行流事件数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code research_run_event} 表的单调自增序号生成、有序事件流持久化与 SSE 游标重放查询。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface ResearchRunEventMapper extends BaseMapper<ResearchRunEventPO> {

    /**
     * 计算并获取指定运行下一条事件的严格单调递增序号
     *
     * @param runId 运行批次 Run ID
     * @return 下一个序列号
     */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM research_run_event WHERE run_id = #{runId}")
    Long nextSequence(@Param("runId") UUID runId);

    /**
     * 插入新的运行流事件（包含载荷 JSONB 格式绑定）
     *
     * @param po 事件持久化实体
     * @return 影响行数
     */
    @Insert("INSERT INTO research_run_event (run_id, user_id, sequence_no, event_type, payload, created_at) " +
            "VALUES (#{runId}, #{userId}, #{sequenceNo}, #{eventType}, CAST(#{payload} AS jsonb), #{createdAt})")
    int insertEvent(ResearchRunEventPO po);

    /**
     * 游标查询指定用户与运行在某一序列号之后的历史事件（用于 SSE 断线重连重放）
     *
     * @param userId          系统用户 ID
     * @param runId           运行批次 Run ID
     * @param afterSequenceNo 起始序列号（不包含该序号）
     * @return 事件列表（按 sequence_no 升序排列）
     */
    @Select("SELECT * FROM research_run_event WHERE user_id = #{userId} AND run_id = #{runId} " +
            "AND sequence_no > #{afterSequenceNo} ORDER BY sequence_no ASC")
    List<ResearchRunEventPO> listAfterSequence(@Param("userId") Long userId, @Param("runId") UUID runId,
                                              @Param("afterSequenceNo") long afterSequenceNo);
}

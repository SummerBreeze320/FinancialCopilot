package com.financial.copilot.agent.core.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis-Plus Mapper 用于长期记忆实体的数据库操作。
 */
@Mapper
public interface LongTermMemoryEntryRepository extends BaseMapper<LongTermMemoryEntry> {
    /**
     * Delete entries older than the given cutoff date.
     */
    @Delete("DELETE FROM long_term_memory WHERE created_at < #{cutoff}")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Find entries for a given session ordered by creation time descending.
     */
    @Select("SELECT * FROM long_term_memory WHERE session_id = #{sessionId} ORDER BY created_at DESC")
    List<LongTermMemoryEntry> findBySessionIdOrderByCreatedAtDesc(@Param("sessionId") String sessionId);

    /**
     * Count entries with same sessionId and content for idempotency.
     */
    @Select("SELECT COUNT(1) FROM long_term_memory WHERE session_id = #{sessionId} AND content = #{content}")
    int countBySessionIdAndContent(@Param("sessionId") String sessionId, @Param("content") String content);
}

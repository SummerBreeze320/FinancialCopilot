package com.financial.copilot.agent.core.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface RefinedFactRepository extends BaseMapper<RefinedFact> {
    @Select("SELECT * FROM refined_fact WHERE session_id = #{sessionId} ORDER BY created_at DESC, id DESC")
    List<RefinedFact> findBySessionIdOrderByCreatedAtDesc(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM refined_fact WHERE session_id LIKE #{prefix} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<RefinedFact> findByUserPrefixOrderByCreatedAtDesc(@Param("prefix") String prefix, @Param("limit") int limit);
}


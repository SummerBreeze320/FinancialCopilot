package com.financial.copilot.agent.core.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

/**
 * 长期记忆实体类，使用 MyBatis-Plus 映射到数据库表。
 * 每条记录关联一个 sessionId，用于在后续会话中检索。
 * content 为抽取的高价值事实或步骤摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(chain = true)
@TableName("long_term_memory")
public class LongTermMemoryEntry {

    /** 主键，自动递增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话标识，用于范围查询 */
    private String sessionId;

    /** 存储的记忆内容 */
    private String content;

    /** 记录创建时间 */
    private LocalDateTime createdAt = LocalDateTime.now();
}
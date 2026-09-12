package com.financial.copilot.agent.core.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("refined_fact")
public class RefinedFact {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String type;
    private String content;
    private LocalDateTime createdAt;

    protected RefinedFact() {}

    public RefinedFact(String sessionId, String type, String content) {
        this.sessionId = sessionId;
        this.type = type;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getType() { return type; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

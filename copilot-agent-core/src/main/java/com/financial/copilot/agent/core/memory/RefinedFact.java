package com.financial.copilot.agent.core.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("refined_fact")
public class RefinedFact {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionKey;
    private String type;
    private String content;
    private LocalDateTime createdAt;

    protected RefinedFact() {}

    public RefinedFact(String sessionKey, String type, String content) {
        this.sessionKey = sessionKey;
        this.type = type;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getSessionKey() { return sessionKey; }
    public String getType() { return type; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

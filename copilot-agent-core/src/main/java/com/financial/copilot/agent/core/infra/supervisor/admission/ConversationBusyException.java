package com.financial.copilot.agent.core.infra.supervisor.admission;

import lombok.Getter;

import java.util.UUID;

/**
 * <h1>会话并发冲突异常</h1>
 * 当同一个会话内已有活跃 Run 正在执行时抛出，触发 HTTP 409 Conflict。
 */
@Getter
public class ConversationBusyException extends RuntimeException {

    private final UUID conversationId;

    public ConversationBusyException(UUID conversationId, String message) {
        super(message);
        this.conversationId = conversationId;
    }
}

package com.financial.copilot.domain.platform.conversation.entity;

import java.util.*;
import java.time.Instant;

import com.financial.copilot.domain.platform.conversation.model.*;

public record ConversationMessage(Long id, UUID conversationId, Long userId, UUID runId, Long sequenceNo,
                                  MessageRole role, MessageStatus status, String content, String errorCode,
                                  String errorMessage, Map<String, Object> metadata, Instant createdAt,
                                  Instant completedAt) {
    public ConversationMessage {
        Objects.requireNonNull(id);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(sequenceNo);
        Objects.requireNonNull(role);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        if (sequenceNo < 1) throw new IllegalArgumentException("Sequence must be positive");
        if (role == MessageRole.USER && status != MessageStatus.COMPLETED)
            throw new IllegalArgumentException("User messages must be completed");
        content = Objects.requireNonNull(content);
        metadata = Map.copyOf(metadata);
    }

    public static ConversationMessage user(Long id, UUID c, Long u, UUID r, Long s, String text, Instant at) {
        return new ConversationMessage(id, c, u, r, s, MessageRole.USER, MessageStatus.COMPLETED, text, null, null, Map.of(), at, at);
    }

    public static ConversationMessage runningAssistant(Long id, UUID c, Long u, UUID r, Long s, Instant at) {
        return new ConversationMessage(id, c, u, r, s, MessageRole.ASSISTANT, MessageStatus.RUNNING, "", null, null, Map.of(), at, null);
    }

    public ConversationMessage complete(String text, Map<String, Object> data, Instant at) {
        return terminal(MessageStatus.COMPLETED, text, null, null, data, at);
    }

    public ConversationMessage fail(String code, String message, Instant at) {
        return terminal(MessageStatus.FAILED, "", code, message, Map.of(), at);
    }

    public ConversationMessage cancel(String message, Instant at) {
        return terminal(MessageStatus.CANCELLED, "", "CANCELLED", message, Map.of(), at);
    }

    private ConversationMessage terminal(MessageStatus target, String text, String code, String message, Map<String, Object> data, Instant at) {
        if (role != MessageRole.ASSISTANT || status != MessageStatus.RUNNING)
            throw new IllegalStateException("Only a running assistant may transition");
        return new ConversationMessage(id, conversationId, userId, runId, sequenceNo, role, target, text, code, message, data, createdAt, Objects.requireNonNull(at));
    }
}

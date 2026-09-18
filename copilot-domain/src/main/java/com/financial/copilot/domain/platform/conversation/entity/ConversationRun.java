package com.financial.copilot.domain.platform.conversation.entity;

import java.util.*;

import com.financial.copilot.domain.platform.conversation.model.MessageStatus;

public record ConversationRun(UUID conversationId, UUID runId, Long userMessageId, Long assistantMessageId,
                              MessageStatus assistantStatus) {
    public ConversationRun {
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(assistantStatus);
        if ((userMessageId == null) != (assistantMessageId == null))
            throw new IllegalArgumentException("Message identifiers must both be present or absent");
    }
}

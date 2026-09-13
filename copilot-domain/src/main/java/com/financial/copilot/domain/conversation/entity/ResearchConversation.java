package com.financial.copilot.domain.conversation.entity;
import java.util.*;
import java.time.Instant;
import com.financial.copilot.domain.conversation.model.ConversationStatus;
public record ResearchConversation(UUID id, Long userId, String title, ConversationStatus status, Instant lastMessageAt, Instant createdAt, Instant updatedAt, Instant deletedAt) {
 public ResearchConversation { Objects.requireNonNull(id); Objects.requireNonNull(userId); Objects.requireNonNull(title); Objects.requireNonNull(status); }
}

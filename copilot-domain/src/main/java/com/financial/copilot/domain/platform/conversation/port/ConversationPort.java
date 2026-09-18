package com.financial.copilot.domain.platform.conversation.port;

import com.financial.copilot.domain.platform.conversation.entity.*;
import com.financial.copilot.domain.platform.conversation.model.*;

import java.util.*;
import java.time.Instant;

public interface ConversationPort {
    ConversationRun createAndStart(Long userId, UUID conversationId, UUID runId, String prompt, Instant now);

    ConversationRun startRun(Long userId, UUID conversationId, UUID runId, String prompt, Instant now);

    Optional<ConversationRun> findRun(Long userId, UUID runId);

    boolean completeAssistant(Long userId, UUID runId, String content, Map<String, Object> metadata, Instant at);

    boolean failAssistant(Long userId, UUID runId, String code, String message, Instant at);

    boolean cancelAssistant(Long userId, UUID runId, String message, Instant at);

    CursorPage<ResearchConversation> listConversations(Long userId, String cursor, int limit);

    CursorPage<ConversationMessage> listMessages(Long userId, UUID conversationId, Long beforeSequence, int limit);

    List<ConversationMessage> recentCompletedMessages(Long userId, UUID conversationId, int limit);

    boolean archive(Long userId, UUID conversationId, Instant at);
}

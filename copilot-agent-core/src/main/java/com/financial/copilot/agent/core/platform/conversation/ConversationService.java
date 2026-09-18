package com.financial.copilot.agent.core.platform.conversation;

import com.financial.copilot.agent.core.infra.event.WorkflowFinishedEvent;
import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import com.financial.copilot.domain.platform.conversation.entity.AgentToolAudit;
import com.financial.copilot.domain.platform.conversation.entity.ConversationMessage;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
import com.financial.copilot.domain.platform.conversation.entity.ResearchConversation;
import com.financial.copilot.domain.platform.conversation.model.CursorPage;
import com.financial.copilot.domain.platform.conversation.model.MessageRole;
import com.financial.copilot.domain.platform.conversation.model.MessageStatus;
import com.financial.copilot.domain.platform.conversation.model.ToolAuditStatus;
import com.financial.copilot.domain.platform.conversation.port.AgentToolAuditPort;
import com.financial.copilot.domain.platform.conversation.port.ConversationPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationPort port;
    private final AgentToolAuditPort audits;
    private final MemoryClient memoryClient;
    private final ConversationPersistenceProperties properties;
    private final ApplicationEventPublisher publisher;
    private final MeterRegistry metrics;

    public ConversationService(
            ConversationPort port,
            AgentToolAuditPort audits,
            MemoryClient memoryClient,
            ConversationPersistenceProperties properties,
            ApplicationEventPublisher publisher,
            @Autowired(required = false) MeterRegistry metrics) {
        this.port = port;
        this.audits = audits;
        this.memoryClient = memoryClient;
        this.properties = properties;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public ConversationRun beginRun(Long userId, UUID requestedId, UUID runId, String prompt) {
        if (!properties.isPersistenceEnabled()) {
            return new ConversationRun(
                    requestedId != null ? requestedId : UUID.randomUUID(),
                    runId, null, null, MessageStatus.RUNNING);
        }
        Instant now = Instant.now();
        if (requestedId == null) {
            UUID conversationId = UUID.randomUUID();
            return port.createAndStart(userId, conversationId, runId, prompt, now);
        }
        return port.startRun(userId, requestedId, runId, prompt, now);
    }

    public void complete(Long userId, ConversationRun run, String sessionKey,
                         String prompt, String report, Map<String, Object> metadata) {
        if (properties.isPersistenceEnabled() && run.assistantMessageId() != null) {
            retryTerminal(() -> port.completeAssistant(userId, run.runId(), report, metadata, Instant.now()));
        }
        memoryClient.addMessage(sessionKey, String.valueOf(userId), "user", prompt);
        memoryClient.addMessage(sessionKey, String.valueOf(userId), "assistant", report);
        publisher.publishEvent(new WorkflowFinishedEvent(this, sessionKey, userId));
    }

    public void fail(Long userId, UUID runId, Throwable error) {
        String code = error.getClass().getSimpleName();
        String message = sanitize(error.getMessage());
        if (properties.isPersistenceEnabled()) {
            retryTerminal(() -> port.failAssistant(userId, runId, code, message, Instant.now()));
            audits.cancelOpenForRun(userId, runId, ToolAuditStatus.FAILED, code, message, Instant.now());
        }
    }

    public void cancel(Long userId, UUID runId, String safeMessage) {
        String message = sanitize(safeMessage);
        if (properties.isPersistenceEnabled()) {
            retryTerminal(() -> port.cancelAssistant(userId, runId, message, Instant.now()));
            audits.cancelOpenForRun(userId, runId, ToolAuditStatus.CANCELLED, "CANCELLED", message, Instant.now());
        }
    }

    public java.util.Optional<ConversationRun> findRun(Long userId, UUID runId) {
        if (!properties.isPersistenceEnabled()) {
            throw new ConversationPersistenceDisabledException();
        }
        return port.findRun(userId, runId);
    }

    public List<String> recentContext(Long userId, UUID conversationId, String sessionKey, int limit) {
        List<String> cached = memoryClient.getContext(sessionKey);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        if (!properties.isPersistenceEnabled()) {
            return List.of();
        }
        List<ConversationMessage> messages = port.recentCompletedMessages(userId, conversationId, limit);
        if (messages.isEmpty()) {
            return List.of();
        }
        Collections.reverse(messages);
        List<String> context = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            String prefix = msg.role() == MessageRole.USER ? "USER: " : "ASSISTANT: ";
            context.add(prefix + msg.content());
        }
        if (metrics != null) {
            metrics.counter("conversation_cache_rebuild_total").increment();
        }
        return context;
    }

    public CursorPage<ResearchConversation> listConversations(Long userId, String cursor, int limit) {
        if (!properties.isPersistenceEnabled()) {
            throw new ConversationPersistenceDisabledException();
        }
        return port.listConversations(userId, cursor, limit);
    }

    public CursorPage<ConversationMessage> listMessages(Long userId, UUID conversationId,
                                                         Long beforeSequence, int limit) {
        if (!properties.isPersistenceEnabled()) {
            throw new ConversationPersistenceDisabledException();
        }
        return port.listMessages(userId, conversationId, beforeSequence, limit);
    }

    public CursorPage<AgentToolAudit> listToolAudits(Long userId, UUID runId, String cursor, int limit) {
        if (!properties.isPersistenceEnabled()) {
            throw new ConversationPersistenceDisabledException();
        }
        return audits.listByRun(userId, runId, cursor, limit);
    }

    public void archive(Long userId, UUID conversationId) {
        if (!properties.isPersistenceEnabled()) {
            throw new ConversationPersistenceDisabledException();
        }
        port.archive(userId, conversationId, Instant.now());
    }

    private void retryTerminal(Runnable operation) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                operation.run();
                return;
            } catch (RuntimeException e) {
                if (e instanceof TransientDataAccessException && attempt < 2) {
                    try {
                        Thread.sleep(attempt == 0 ? 50 : 200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }

    private static String sanitize(String message) {
        if (message == null) return "";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}

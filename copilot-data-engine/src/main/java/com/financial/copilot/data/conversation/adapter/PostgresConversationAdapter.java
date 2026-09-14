package com.financial.copilot.data.conversation.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.data.conversation.mapper.AgentToolAuditMapper;
import com.financial.copilot.data.conversation.mapper.ConversationMessageMapper;
import com.financial.copilot.data.conversation.mapper.ResearchConversationMapper;
import com.financial.copilot.data.conversation.po.AgentToolAuditPO;
import com.financial.copilot.data.conversation.po.ConversationMessagePO;
import com.financial.copilot.data.conversation.po.ResearchConversationPO;
import com.financial.copilot.domain.conversation.entity.AgentToolAudit;
import com.financial.copilot.domain.conversation.entity.ConversationMessage;
import com.financial.copilot.domain.conversation.entity.ConversationRun;
import com.financial.copilot.domain.conversation.entity.ResearchConversation;
import com.financial.copilot.domain.conversation.exception.ConversationNotFoundException;
import com.financial.copilot.domain.conversation.model.ConversationStatus;
import com.financial.copilot.domain.conversation.model.CursorPage;
import com.financial.copilot.domain.conversation.model.MessageRole;
import com.financial.copilot.domain.conversation.model.MessageStatus;
import com.financial.copilot.domain.conversation.model.ToolAuditStatus;
import com.financial.copilot.domain.conversation.port.AgentToolAuditPort;
import com.financial.copilot.domain.conversation.port.ConversationPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * <h1>基于 PostgreSQL 的金融投研会话与工具调用审计持久化适配器</h1>
 * <p>
 * 实现领域层 {@link ConversationPort} 与 {@link AgentToolAuditPort} SPI 端口，
 * 基于 MyBatis-Plus Mapper 操作关系表，完成复杂多智能体协同运行流的消息归档、行级排他锁、游标翻页及调用轨迹审计。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresConversationAdapter implements ConversationPort, AgentToolAuditPort {

    private final ResearchConversationMapper conversations;
    private final ConversationMessageMapper messages;
    private final AgentToolAuditMapper audits;
    private final ObjectMapper json;


    @Override
    @Transactional("jdbcTransactionManager")
    public ConversationRun createAndStart(Long userId, UUID conversationId, UUID runId,
                                          String prompt, Instant now) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(runId);
        LocalDateTime at = toLocalDateTime(now);
        String title = titleFromPrompt(prompt);

        ResearchConversationPO conv = ResearchConversationPO.builder()
                .id(conversationId).userId(userId).title(title).status("ACTIVE")
                .lastMessageAt(at).createdAt(at).updatedAt(at).build();
        conversations.insert(conv);

        long seq = messages.nextSequence(conversationId);
        ConversationMessagePO userMsg = messagePO(conversationId, userId, runId, seq, "USER", "COMPLETED",
                prompt, "{}", at, at);
        messages.insertMessage(userMsg);

        ConversationMessagePO assistantMsg = messagePO(conversationId, userId, runId, seq + 1,
                "ASSISTANT", "RUNNING", "", "{}", at, null);
        messages.insertMessage(assistantMsg);

        conversations.touchLastMessage(conversationId, at);
        return new ConversationRun(conversationId, runId, userMsg.getId(), assistantMsg.getId(), MessageStatus.RUNNING);
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public ConversationRun startRun(Long userId, UUID conversationId, UUID runId,
                                    String prompt, Instant now) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(runId);
        ResearchConversationPO conv = conversations.lockActive(userId, conversationId);
        if (conv == null) throw new ConversationNotFoundException();
        LocalDateTime at = toLocalDateTime(now);

        long seq = messages.nextSequence(conversationId);
        ConversationMessagePO userMsg = messagePO(conversationId, userId, runId, seq, "USER", "COMPLETED",
                prompt, "{}", at, at);
        messages.insertMessage(userMsg);

        ConversationMessagePO assistantMsg = messagePO(conversationId, userId, runId, seq + 1,
                "ASSISTANT", "RUNNING", "", "{}", at, null);
        messages.insertMessage(assistantMsg);

        conversations.touchLastMessage(conversationId, at);
        return new ConversationRun(conversationId, runId, userMsg.getId(), assistantMsg.getId(), MessageStatus.RUNNING);
    }

    @Override
    public Optional<ConversationRun> findRun(Long userId, UUID runId) {
        List<ConversationMessagePO> rows = messages.findByRun(userId, runId);
        if (rows.isEmpty()) return Optional.empty();
        UUID conversationId = rows.get(0).getConversationId();
        Long userMessageId = null, assistantMessageId = null;
        MessageStatus assistantStatus = null;
        for (ConversationMessagePO msg : rows) {
            if ("USER".equals(msg.getRole())) userMessageId = msg.getId();
            if ("ASSISTANT".equals(msg.getRole())) {
                assistantMessageId = msg.getId();
                assistantStatus = MessageStatus.valueOf(msg.getStatus());
            }
        }
        return Optional.of(new ConversationRun(conversationId, runId, userMessageId, assistantMessageId, assistantStatus));
    }

    @Override
    public boolean completeAssistant(Long userId, UUID runId, String content,
                                     Map<String, Object> metadata, Instant at) {
        return messages.completeAssistant(userId, runId, content, toJson(metadata),
                toLocalDateTime(at)) == 1;
    }

    @Override
    public boolean failAssistant(Long userId, UUID runId, String code, String message, Instant at) {
        return messages.failAssistant(userId, runId, code, message, toLocalDateTime(at)) == 1;
    }

    @Override
    public boolean cancelAssistant(Long userId, UUID runId, String message, Instant at) {
        return messages.cancelAssistant(userId, runId, message, toLocalDateTime(at)) == 1;
    }

    @Override
    public CursorPage<ResearchConversation> listConversations(Long userId, String cursor, int limit) {
        LocalDateTime cursorAt = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            Map<String, Object> decoded = decodeCursor(cursor);
            cursorAt = parseLocalDateTime(decoded.get("at"));
            cursorId = parseUUID(decoded.get("id"));
        }
        List<ResearchConversationPO> rows = conversations.listConversations(userId, cursorAt, cursorId, limit + 1);
        return toCursorPage(rows, limit, this::toConversationDomain, this::conversationCursor);
    }

    @Override
    public CursorPage<ConversationMessage> listMessages(Long userId, UUID conversationId,
                                                         Long beforeSequence, int limit) {
        if (conversations.findOwned(userId, conversationId) == null) {
            throw new ConversationNotFoundException();
        }
        List<ConversationMessagePO> rows = messages.listMessages(userId, conversationId, beforeSequence, limit + 1);
        return toCursorPage(rows, limit, this::toMessageDomain, this::messageCursor);
    }

    @Override
    public List<ConversationMessage> recentCompletedMessages(Long userId, UUID conversationId, int limit) {
        List<ConversationMessagePO> rows = messages.recentCompletedMessages(userId, conversationId, limit);
        return rows.stream().map(this::toMessageDomain).toList();
    }

    @Override
    public boolean archive(Long userId, UUID conversationId, Instant at) {
        return conversations.archive(userId, conversationId, toLocalDateTime(at)) == 1;
    }

    @Override
    public void start(AgentToolAudit audit) {
        AgentToolAuditPO po = AgentToolAuditPO.builder()
                .conversationId(audit.conversationId())
                .assistantMessageId(audit.assistantMessageId())
                .userId(audit.userId())
                .runId(audit.runId())
                .nodeId(audit.nodeId())
                .agentName(audit.agentName())
                .toolCallId(audit.toolCallId())
                .toolName(audit.toolName())
                .status(audit.status().name())
                .arguments(toJson(audit.arguments()))
                .startedAt(toLocalDateTime(audit.startedAt()))
                .build();
        audits.start(po);
    }

    @Override
    public void complete(Long userId, UUID runId, String toolCallId, ToolAuditStatus status,
                         String summary, String hash, List<String> artifactIds,
                         String errorCode, String errorMessage, Instant at, long durationMs) {
        audits.complete(userId, runId, toolCallId, status.name(), summary, hash,
                toJsonList(artifactIds), errorCode, errorMessage, toLocalDateTime(at), durationMs);
    }

    @Override
    public void cancelOpenForRun(Long userId, UUID runId, ToolAuditStatus status,
                                 String errorCode, String errorMessage, Instant at) {
        audits.cancelOpenForRun(userId, runId, status.name(), errorCode, errorMessage, toLocalDateTime(at));
    }

    @Override
    public CursorPage<AgentToolAudit> listByRun(Long userId, UUID runId, String cursor, int limit) {
        if (messages.findAssistantByRun(userId, runId) == null) {
            throw new ConversationNotFoundException();
        }
        Long beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            Map<String, Object> decoded = decodeCursor(cursor);
            beforeId = parseLong(decoded.get("id"));
        }
        List<AgentToolAuditPO> rows = audits.listByRun(userId, runId, beforeId, limit + 1);
        return toCursorPage(rows, limit, this::toAuditDomain, this::auditCursor);
    }

    private <T, P> CursorPage<T> toCursorPage(List<P> rows, int limit,
                                             Function<P, T> mapper,
                                             Function<P, String> cursorFn) {
        boolean hasNext = rows.size() > limit;
        List<P> page = hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = hasNext ? cursorFn.apply(page.get(page.size() - 1)) : null;
        return new CursorPage<>(page.stream().map(mapper).toList(), nextCursor);
    }

    private String conversationCursor(ResearchConversationPO po) {
        return encodeCursor("at", po.getLastMessageAt().toString(), "id", po.getId().toString());
    }

    private String messageCursor(ConversationMessagePO po) {
        return encodeCursor("seq", po.getSequenceNo());
    }

    private String auditCursor(AgentToolAuditPO po) {
        return encodeCursor("id", po.getId());
    }

    private String encodeCursor(Object... kv) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
            byte[] encoded = Base64.getUrlEncoder().encode(json.writeValueAsBytes(map));
            return new String(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> decodeCursor(String cursor) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = json.readValue(decoded, Map.class);
            return map;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed cursor");
        }
    }

    private String toJson(Map<String, Object> map) {
        try { return json.writeValueAsString(map == null ? Map.of() : map); }
        catch (Exception e) { return "{}"; }
    }

    private String toJsonList(List<String> list) {
        try { return json.writeValueAsString(list == null ? List.of() : list); }
        catch (Exception e) { return "[]"; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return Map.of();
        try { return json.readValue(jsonStr, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonList(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return List.of();
        try { return json.readValue(jsonStr, List.class); }
        catch (Exception e) { return List.of(); }
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt.toInstant(ZoneOffset.UTC);
    }

    private static String titleFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return "New Conversation";
        String trimmed = prompt.strip();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints <= 40) return trimmed;
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 40));
    }

    private static ConversationMessagePO messagePO(UUID conversationId, Long userId, UUID runId,
                                                    long seq, String role, String status,
                                                    String content, String metadata,
                                                    LocalDateTime createdAt, LocalDateTime completedAt) {
        return ConversationMessagePO.builder()
                .conversationId(conversationId).userId(userId).runId(runId).sequenceNo(seq)
                .role(role).status(status).content(content).metadata(metadata)
                .createdAt(createdAt).completedAt(completedAt).build();
    }

    private ResearchConversation toConversationDomain(ResearchConversationPO po) {
        return new ResearchConversation(po.getId(), po.getUserId(), po.getTitle(),
                ConversationStatus.valueOf(po.getStatus()),
                toInstant(po.getLastMessageAt()), toInstant(po.getCreatedAt()),
                toInstant(po.getUpdatedAt()),
                po.getDeletedAt() != null ? toInstant(po.getDeletedAt()) : null);
    }

    private ConversationMessage toMessageDomain(ConversationMessagePO po) {
        return new ConversationMessage(po.getId(), po.getConversationId(), po.getUserId(),
                po.getRunId(), po.getSequenceNo(), MessageRole.valueOf(po.getRole()),
                MessageStatus.valueOf(po.getStatus()), po.getContent(),
                po.getErrorCode(), po.getErrorMessage(), fromJson(po.getMetadata()),
                toInstant(po.getCreatedAt()),
                po.getCompletedAt() != null ? toInstant(po.getCompletedAt()) : null);
    }

    private AgentToolAudit toAuditDomain(AgentToolAuditPO po) {
        return new AgentToolAudit(po.getId(), po.getConversationId(), po.getAssistantMessageId(),
                po.getUserId(), po.getRunId(), po.getNodeId(), po.getAgentName(),
                po.getToolCallId(), po.getToolName(), ToolAuditStatus.valueOf(po.getStatus()),
                fromJson(po.getArguments()), po.getResultSummary(), po.getResultHash(),
                fromJsonList(po.getArtifactIds()), toInstant(po.getStartedAt()),
                po.getCompletedAt() != null ? toInstant(po.getCompletedAt()) : null,
                po.getDurationMs(), po.getErrorCode(), po.getErrorMessage());
    }

    private static LocalDateTime parseLocalDateTime(Object value) {
        if (value == null) return null;
        return LocalDateTime.parse(value.toString());
    }

    private static UUID parseUUID(Object value) {
        if (value == null) return null;
        return UUID.fromString(value.toString());
    }

    private static Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString());
    }
}

package com.financial.copilot.controller;

import com.financial.copilot.agent.core.platform.conversation.ConversationPersistenceDisabledException;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.platform.conversation.entity.AgentToolAudit;
import com.financial.copilot.domain.platform.conversation.entity.ConversationMessage;
import com.financial.copilot.domain.platform.conversation.entity.ResearchConversation;
import com.financial.copilot.domain.platform.conversation.exception.ConversationNotFoundException;
import com.financial.copilot.domain.platform.conversation.model.CursorPage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * <h1>对话历史与归档 Controller</h1>
 * <p>
 * 提供对话列表、消息分页、归档等查询接口。所有接口均基于当前认证用户的 userId 进行隔离。
 * 当对话持久化功能被禁用时，查询类接口统一返回 503 CONVERSATION_PERSISTENCE_DISABLED。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 分页查询当前用户的对话列表（按最后消息时间倒序）。
     *
     * @param cursor 分页游标，为空表示第一页
     * @param limit  每页条数，范围 1..100
     */
    @GetMapping
    public Mono<ApiResult<CursorPage<ResearchConversation>>> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            int safeLimit = clampLimit(limit);
            try {
                CursorPage<ResearchConversation> page = conversationService.listConversations(uid, cursor, safeLimit);
                return ApiResult.success(page);
            } catch (ConversationPersistenceDisabledException e) {
                throw persistenceDisabled();
            }
        });
    }

    /**
     * 分页查询指定对话的消息列表（按序号倒序）。
     *
     * @param conversationId 对话 ID
     * @param beforeSequence 返回序号小于该值的消息，为空表示从最新消息开始
     * @param limit          每页条数，范围 1..100
     */
    @GetMapping("/{conversationId}/messages")
    public Mono<ApiResult<CursorPage<ConversationMessage>>> messages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(defaultValue = "20") int limit) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            int safeLimit = clampLimit(limit);
            try {
                CursorPage<ConversationMessage> page = conversationService.listMessages(
                        uid, conversationId, beforeSequence, safeLimit);
                return ApiResult.success(page);
            } catch (ConversationNotFoundException e) {
                throw notFound();
            } catch (ConversationPersistenceDisabledException e) {
                throw persistenceDisabled();
            }
        });
    }

    /**
     * 归档指定对话（软删除，不再出现在列表中）。
     *
     * @param conversationId 对话 ID
     */
    @PostMapping("/{conversationId}/archive")
    public Mono<ApiResult<Map<String, Object>>> archive(@PathVariable UUID conversationId) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            try {
                conversationService.archive(uid, conversationId);
                return ApiResult.success(Map.of(
                        "conversationId", conversationId,
                        "archived", true
                ));
            } catch (ConversationNotFoundException e) {
                throw notFound();
            } catch (ConversationPersistenceDisabledException e) {
                throw persistenceDisabled();
            }
        });
    }

    private static int clampLimit(int limit) {
        if (limit < 1) return 1;
        return Math.min(limit, 100);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
    }

    private ResponseStatusException persistenceDisabled() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "CONVERSATION_PERSISTENCE_DISABLED");
    }
}

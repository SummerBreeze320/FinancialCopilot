package com.financial.copilot.agent.core.infra.memory;

import com.financial.copilot.agent.core.infra.event.WorkflowFinishedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户画像提取异步任务。
 * 监听 {@link WorkflowFinishedEvent}，从会话上下文中提取用户画像维度并写入 Graphiti。
 * 原事实提取逻辑已由 Graphiti Episode 内部自动完成，本任务仅负责画像维度更新。
 */
@Slf4j
@Component
public class MemoryRefinementTask {

    private final MemoryClient memoryClient;

    public MemoryRefinementTask(MemoryClient memoryClient) {
        this.memoryClient = memoryClient;
    }

    @EventListener
    @Async("taskExecutor")
    public void handleWorkflowFinished(WorkflowFinishedEvent event) {
        try {
            String sessionKey = event.getSessionKey();
            Long userId = event.getUserId();
            if (userId == null) {
                log.warn("[ProfileExtraction] No userId in event for session {}", sessionKey);
                return;
            }

            List<String> context = memoryClient.getContext(sessionKey);
            if (context == null || context.isEmpty()) {
                log.warn("[ProfileExtraction] No context for session {}", sessionKey);
                return;
            }

            String dialogue = String.join("\n", context);
            memoryClient.extractProfile(String.valueOf(userId), dialogue);
            log.info("[ProfileExtraction] Triggered for user {} session {}", userId, sessionKey);
        } catch (Exception e) {
            log.error("[ProfileExtraction] Failed for session {}: {}", event.getSessionKey(), e.getMessage(), e);
        }
    }
}

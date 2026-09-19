package com.financial.copilot.agent.core.memory;

import com.financial.copilot.agent.core.event.WorkflowFinishedEvent;
import com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient;
import com.financial.copilot.agent.core.memory.remote.dto.ProcessSessionRequestDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * <h1>短期记忆语义提纯异步任务 (Memory Refinement Task)</h1>
 * <p>
 * 监听工作流完结事件 {@link WorkflowFinishedEvent}，将当前会话的短期上下文
 * 异步投递至基于 Mem0 与 ReMe 驱动的 Python 长期记忆微服务离线加工管道。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class MemoryRefinementTask {

    private final ShortTermMemoryService shortTermMemoryService;
    private final RemoteMemoryServiceClient remoteMemoryServiceClient;

    public MemoryRefinementTask(ShortTermMemoryService shortTermMemoryService) {
        this(shortTermMemoryService, null);
    }

    @Autowired
    public MemoryRefinementTask(ShortTermMemoryService shortTermMemoryService,
                                @Autowired(required = false) RemoteMemoryServiceClient remoteMemoryServiceClient) {
        this.shortTermMemoryService = shortTermMemoryService;
        this.remoteMemoryServiceClient = remoteMemoryServiceClient;
    }

    /**
     * 工作流结束后的异步处理入口：提取会话短期记忆，投递至远程 Python 长期记忆离线提纯管道
     */
    @EventListener
    @Async("taskExecutor")
    public void handleWorkflowFinished(WorkflowFinishedEvent event) {
        try {
            refineAndRecord(event.getSessionKey());
        } catch (Exception e) {
            log.error("[MemoryRefinement] Failed handling WorkflowFinishedEvent for session {}: {}", event.getSessionKey(), e.getMessage(), e);
        }
    }

    public void refineAndRecord(String sessionKey) {
        try {
            List<String> context = shortTermMemoryService.getContext(sessionKey);
            if (context == null || context.isEmpty()) {
                log.warn("[MemoryRefinement] No short-term memory found for session {}", sessionKey);
                return;
            }

            if (remoteMemoryServiceClient != null && remoteMemoryServiceClient.isEnabled()) {
                String userId = sessionKey.contains(":") ? sessionKey.substring(0, sessionKey.indexOf(":")) : sessionKey;
                ProcessSessionRequestDTO req = ProcessSessionRequestDTO.builder()
                        .sessionId(sessionKey)
                        .userId(userId)
                        .sessionData(Map.of("context", context))
                        .build();
                remoteMemoryServiceClient.processSessionAsync(req);
                log.info("[MemoryRefinement] Session context dispatched to remote memory service: session={}", sessionKey);
            }
        } catch (Exception e) {
            log.error("[MemoryRefinement] Failed dispatching for session {}: {}", sessionKey, e.getMessage(), e);
        }
    }
}

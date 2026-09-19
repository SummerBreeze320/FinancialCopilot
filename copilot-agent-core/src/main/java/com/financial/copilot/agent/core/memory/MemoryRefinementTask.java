package com.financial.copilot.agent.core.memory;

import com.financial.copilot.agent.core.event.WorkflowFinishedEvent;
import com.financial.copilot.agent.core.llm.service.LlmService;
import com.financial.copilot.agent.core.prompt.MemoryRefinementPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <h1>短期记忆语义提纯异步任务 (Memory Refinement Task)</h1>
 * <p>
 * 监听工作流完结事件 {@link WorkflowFinishedEvent}，调用大模型对当前会话的短期交互历史
 * 进行事实提取与噪音过滤，将高价值事实持久化至长期记忆库。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class MemoryRefinementTask {

    private final ShortTermMemoryService shortTermMemoryService;
    private final LongTermMemoryService longTermMemoryService;
    private final LlmService llmService;
    private final com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient remoteMemoryServiceClient;

    public MemoryRefinementTask(ShortTermMemoryService shortTermMemoryService,
                               LongTermMemoryService longTermMemoryService,
                               LlmService llmService) {
        this(shortTermMemoryService, longTermMemoryService, llmService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryRefinementTask(ShortTermMemoryService shortTermMemoryService,
                               LongTermMemoryService longTermMemoryService,
                               LlmService llmService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient remoteMemoryServiceClient) {
        this.shortTermMemoryService = shortTermMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.llmService = llmService;
        this.remoteMemoryServiceClient = remoteMemoryServiceClient;
    }

    /**
     * Runs asynchronously after a workflow finishes. It extracts the short‑term memory for the
     * given session, asks the LLM to summarise factual statements, parses the result and stores
     * each fact as a refined entry in long‑term memory.
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
                log.warn("[MemoryRefinement] No short‑term memory found for session {}", sessionKey);
                return;
            }
            String joined = String.join("\n", context);
            var spec = MemoryRefinementPrompt.buildSpec(joined);
            String llmResponse = llmService.chat(spec.toLlmRequest());
            List<String> facts = llmResponse.lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .collect(Collectors.toList());
            if (facts.isEmpty()) {
                log.warn("[MemoryRefinement] LLM returned no facts for session {}", sessionKey);
                return;
            }
            longTermMemoryService.recordRefinedFacts(sessionKey, facts);
            log.info("[MemoryRefinement] Recorded {} refined facts for session {}", facts.size(), sessionKey);

            if (remoteMemoryServiceClient != null && remoteMemoryServiceClient.isEnabled()) {
                try {
                    String userId = sessionKey.contains(":") ? sessionKey.substring(0, sessionKey.indexOf(":")) : sessionKey;
                    com.financial.copilot.agent.core.memory.remote.dto.ProcessSessionRequestDTO req =
                            com.financial.copilot.agent.core.memory.remote.dto.ProcessSessionRequestDTO.builder()
                                    .sessionId(sessionKey)
                                    .userId(userId)
                                    .sessionData(java.util.Map.of("context", context, "refined_facts", facts))
                                    .build();
                    remoteMemoryServiceClient.processSessionAsync(req);
                } catch (Exception ex) {
                    log.warn("[MemoryRefinement] Failed sending session to remote memory service: {}", ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[MemoryRefinement] Failed for session {}: {}", sessionKey, e.getMessage(), e);
        }
    }

}

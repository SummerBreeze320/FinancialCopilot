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

    public MemoryRefinementTask(ShortTermMemoryService shortTermMemoryService,
                               LongTermMemoryService longTermMemoryService,
                               LlmService llmService) {
        this.shortTermMemoryService = shortTermMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.llmService = llmService;
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
            refineAndRecord(event.getSessionId());
        } catch (Exception e) {
            log.error("[MemoryRefinement] Failed handling WorkflowFinishedEvent for session {}: {}", event.getSessionId(), e.getMessage(), e);
        }
    }
    public void refineAndRecord(String sessionId) {
        try {
            List<String> context = shortTermMemoryService.getContext(sessionId);
            if (context == null || context.isEmpty()) {
                log.warn("[MemoryRefinement] No short‑term memory found for session {}", sessionId);
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
                log.warn("[MemoryRefinement] LLM returned no facts for session {}", sessionId);
                return;
            }
            longTermMemoryService.recordRefinedFacts(sessionId, facts);
            log.info("[MemoryRefinement] Recorded {} refined facts for session {}", facts.size(), sessionId);
        } catch (Exception e) {
            log.error("[MemoryRefinement] Failed for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

}

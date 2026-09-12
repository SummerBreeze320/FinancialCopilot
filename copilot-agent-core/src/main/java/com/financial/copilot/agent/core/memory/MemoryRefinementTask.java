package com.financial.copilot.agent.core.memory;

import com.financial.copilot.agent.core.llm.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.util.List;
import com.financial.copilot.agent.core.event.WorkflowFinishedEvent;
import org.springframework.context.event.EventListener;
import java.util.stream.Collectors;

/**
 * Async task that refines short‑term memory using the LLM and persists the extracted facts
 * into long‑term memory.
 */
@Component
public class MemoryRefinementTask {

    private static final Logger log = LoggerFactory.getLogger(MemoryRefinementTask.class);

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
            var spec = com.financial.copilot.agent.core.prompt.MemoryRefinementPrompt.buildSpec(joined);
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

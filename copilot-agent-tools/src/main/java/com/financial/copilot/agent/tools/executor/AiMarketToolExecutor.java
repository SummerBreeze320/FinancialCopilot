package com.financial.copilot.agent.tools.executor;

import com.financial.copilot.agent.tools.client.AiMarketClient;
import com.financial.copilot.agent.tools.distiller.ComponentDataDistiller;
import com.financial.copilot.agent.tools.model.ToolDefinition;
import com.financial.copilot.agent.tools.model.ToolExecuteRequest;
import com.financial.copilot.agent.tools.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.model.UITreeComponent;
import com.financial.copilot.agent.tools.workspace.ConfiguredToolWorkspaceBuilder;
import com.financial.copilot.agent.tools.workspace.ToolWorkspacePayload;
import com.financial.copilot.agent.tools.workspace.ToolWorkspaceReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * <h1>AIMarket 开放工具市场执行器 (AiMarketToolExecutor)</h1>
 * <p>
 * 负责对接 Wind AIMarket 托管的通用 AI 工具 (如研报语义检索 fin_doc_searchV3、综合资料搜索 aggregate_search 等)，
 * 提供金融非结构化文本检索（RAG 增强）能力，填补量化指标之外的研报观点与定性逻辑分析。
 * </p>
 */
@Slf4j
@Component
public class AiMarketToolExecutor implements ToolExecutor {

    private final ComponentDataDistiller distiller;
    private final ConfiguredToolWorkspaceBuilder workspaceBuilder;
    private final AiMarketClient aiMarketClient;

    @Autowired
    public AiMarketToolExecutor(ComponentDataDistiller distiller,
                                ConfiguredToolWorkspaceBuilder workspaceBuilder,
                                @Autowired(required = false) AiMarketClient aiMarketClient) {
        this.distiller = distiller;
        this.workspaceBuilder = workspaceBuilder;
        this.aiMarketClient = aiMarketClient;
    }

    public AiMarketToolExecutor(ComponentDataDistiller distiller, ConfiguredToolWorkspaceBuilder workspaceBuilder) {
        this(distiller, workspaceBuilder, null);
    }

    @Override
    public boolean supports(ToolDefinition definition, ToolExecuteRequest request) {
        String provider = definition.getProvider();
        if (provider == null) {
            return false;
        }
        String p = provider.trim().toLowerCase();
        return "http_aimarket".equals(p) || "aimarket".equals(p) || "wind_aimarket".equals(p);
    }

    @Override
    public ToolExecuteResult execute(ToolDefinition definition, ToolExecuteRequest request) {
        String targetTool = definition.getTargetTool() != null && !definition.getTargetTool().isBlank()
                ? definition.getTargetTool()
                : definition.getId();

        log.info("Executing AIMarket tool: {} [targetTool={}, provider={}]",
                definition.getId(), targetTool, definition.getProvider());

        try {
            Map<String, Object> rawData = new HashMap<>();
            rawData.put("toolId", definition.getId());
            rawData.put("targetTool", targetTool);
            rawData.put("provider", definition.getProvider());
            rawData.put("arguments", request.getArguments());

            String toolText = null;
            Object toolPayload = null;

            if (aiMarketClient != null) {
                String sessionId = request.getSessionId();
                Map<String, Object> callResult = aiMarketClient.call(sessionId, targetTool, request.getArguments());
                if (callResult != null) {
                    rawData.put("aiMarketResult", callResult);
                    Object textObj = callResult.get("tool_text");
                    if (textObj instanceof String s && !s.isBlank()) {
                        toolText = s;
                    }
                    toolPayload = callResult.get("tool_payload");
                    if (toolPayload != null) {
                        rawData.put("tool_payload", toolPayload);
                    }
                }
            } else {
                log.warn("AiMarketClient is not injected; running in fallback mode for tool: {}", definition.getId());
            }

            StringBuilder llmTextBuilder = new StringBuilder();
            llmTextBuilder.append("【").append(definition.getName()).append(" 研报/知识检索结果】:\n");

            if (toolText != null) {
                llmTextBuilder.append(toolText).append("\n\n");
            } else {
                llmTextBuilder.append("（知识库已检索，未匹配到相关研报或服务未返回文本）\n\n");
            }

            List<UITreeComponent> components = definition.getComponents() != null ? definition.getComponents() : List.of();
            for (UITreeComponent comp : components) {
                try {
                    String distilled = distiller.distill(comp);
                    llmTextBuilder.append(distilled).append("\n\n");
                } catch (Exception ex) {
                    log.warn("Distillation failed for component: {}", comp.getId(), ex);
                }
            }

            UITreeComponent primary = components.isEmpty() ? null : components.getFirst();
            ToolWorkspacePayload workspace = workspaceBuilder.build(definition, request, components);
            List<ToolWorkspaceReference> references = workspaceBuilder.references(workspace);

            return ToolExecuteResult.builder()
                    .success(true)
                    .textForLlm(llmTextBuilder.toString().trim())
                    .visualComponent(primary)
                    .visualComponents(components)
                    .workspacePayload(workspace)
                    .references(references)
                    .rawData(rawData)
                    .build();
        } catch (Exception e) {
            log.error("AiMarketToolExecutor failed for tool: {}", definition.getId(), e);
            return ToolExecuteResult.ofFailure("AIMarket 调用异常: " + e.getMessage());
        }
    }
}

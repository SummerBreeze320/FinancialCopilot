package com.financial.copilot.agent.core.infra.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agent.AgentRole;
import com.financial.copilot.agent.core.infra.evidence.AgentEvidence;
import com.financial.copilot.agent.core.infra.evidence.RunEvidenceStore;
import com.financial.copilot.agent.core.infra.runtime.ResearchExecutionContext;
import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <h1>全流程研报证据与适格性画像只读检视工具集 (ResearchEvidenceTools)</h1>
 * <p>
 * 面向 {@link AgentRole#RISK_REVIEW} 与 {@link AgentRole#REPORT} 角色。
 * 遵循 Tool-as-Truth 规范，允许审查员与研报撰写者读取当前运行任务中
 * 各领域子智能体沉淀的确定性客观事实证据及投资人画像。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ResearchEvidenceTools {

    private final RunEvidenceStore evidenceStore;
    private final ObjectMapper objectMapper;

    @Autowired
    public ResearchEvidenceTools(RunEvidenceStore evidenceStore, @Autowired(required = false) ObjectMapper objectMapper) {
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore cannot be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Tool(description = "读取当前投研任务全流程所收集到的所有真实量化事实证据快照（包括基金档案、量化指标、股票财报、知识图谱等）")
    public String getCollectedEvidences(ToolContext context) {
        try {
            ResearchExecutionContext execCtx = ResearchToolContext.extractExecutionContext(context);
            List<AgentEvidence> snapshot = evidenceStore.getSnapshot(execCtx.workflowRunId());
            log.info("[EVIDENCE-TOOL] 读取全量证据快照: runId={}, count={}", execCtx.workflowRunId(), snapshot.size());
            if (snapshot.isEmpty()) {
                return "{\"evidences\": [], \"message\": \"当前暂无已沉淀的客观事实证据\"}";
            }
            return objectMapper.writeValueAsString(Map.of(
                    "runId", execCtx.workflowRunId(),
                    "totalCount", snapshot.size(),
                    "evidences", snapshot
            ));
        } catch (Exception e) {
            log.error("[EVIDENCE-TOOL] 读取证据快照异常: {}", e.getMessage(), e);
            return "{\"evidences\": [], \"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "读取当前投资者的适格性画像与风控约束（风险评级 C1-C5、投资期限、最大承受回撤等）")
    public String getUserInvestmentProfile(ToolContext context) {
        try {
            ResearchExecutionContext execCtx = ResearchToolContext.extractExecutionContext(context);
            var snapshot = execCtx.conversation();
            if (snapshot != null && snapshot.investmentProfile() != null) {
                UserInvestmentProfile profile = snapshot.investmentProfile();
                log.info("[EVIDENCE-TOOL] 读取投资者画像: userId={}, riskLevel={}",
                        profile.getUserId(), profile.getRiskToleranceLevel());
                return objectMapper.writeValueAsString(profile);
            }
            return "{\"message\": \"未设置特定投资人画像，默认按中等风险偏好 (C3) 评估\"}";
        } catch (Exception e) {
            log.error("[EVIDENCE-TOOL] 读取投资者画像异常: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}

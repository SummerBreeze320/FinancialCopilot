package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.llm.service.LlmService;
import com.financial.copilot.agent.core.prompt.FundScreenerPrompt;
import com.financial.copilot.agent.core.prompt.RTCFPromptSpec;
import com.financial.copilot.agent.core.skill.SkillMatcher;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.domain.fund.entity.FundInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * <h1>公募基金智能筛选专员 Agent (Fund Screener)</h1>
 * <p>
 * 职责：负责将用户自然语言诉求通过大模型精准提取为强类型选基 DSL 条件 {@link FundScreeningCriteria}，
 * 并调用底层基金只读筛选工具获取候选标的池。
 * 遵循 RTCF 规范化提示词模型，确保系统前缀静态化与输出格式强约束。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundScreenerAgent {

    private final LlmService clientService;
    private final FundScreeningTool screeningTool;
    private final ObjectMapper objectMapper;
    private final SkillMatcher skillMatcher;

    public FundScreenerAgent(LlmService clientService, FundScreeningTool screeningTool, ObjectMapper objectMapper) {
        this(clientService, screeningTool, objectMapper, null);
    }

    @Autowired
    public FundScreenerAgent(
            LlmService clientService,
            FundScreeningTool screeningTool,
            ObjectMapper objectMapper,
            @Autowired(required = false) SkillMatcher skillMatcher
    ) {
        this.clientService = clientService;
        this.screeningTool = screeningTool;
        this.objectMapper = objectMapper;
        this.skillMatcher = skillMatcher;
    }

    /**
     * 执行自然语言基金初筛
     *
     * @param userPrompt 用户输入的问题或选基要求
     * @return 命中基金的 JSON 格式列表字符串
     */
    public String executeScreening(String userPrompt) {
        log.info("[FUND-SCREENER] 正在进行基金自然语言筛选: prompt={}", userPrompt);
        String skillRules = (skillMatcher != null) ? skillMatcher.matchSkillInstructions("SCREENING", userPrompt) : "";
        RTCFPromptSpec spec = FundScreenerPrompt.buildSpec(userPrompt, skillRules);

        String response = "";
        try {
            response = clientService.chat(spec.toLlmRequest());
        } catch (Exception e) {
            log.warn("[FUND-SCREENER] 调用 LLM 筛选意图解析失败，启用稳健基础条件: error={}", e.getMessage());
        }

        FundScreeningCriteria criteria;
        try {
            String cleanJson = (response != null) ? response.trim() : "";
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            criteria = objectMapper.readValue(cleanJson.trim(), FundScreeningCriteria.class);
        } catch (Exception e) {
            log.warn("[FUND-SCREENER] 解析筛选 Criteria 失败，启用稳健基础条件: error={}", e.getMessage());
            criteria = new FundScreeningCriteria("偏股混合型", null, 5.0, null, null, null, null, null, "SCALE", "DESC", 10);
        }

        return screeningTool.screenFunds(criteria);
    }

    /**
     * 强类型 DAG 节点筛选执行入口
     *
     * @param node       当前 DAG 节点
     * @param userPrompt 用户原始提问或筛选指令
     * @return 强类型标的池产物
     */
    public Artifact<FundPool> screenArtifact(GraphNode node, String userPrompt) {
        String rawJson = executeScreening(userPrompt);
        List<FundInfo> funds = List.of();
        try {
            funds = objectMapper.readValue(rawJson, objectMapper.getTypeFactory().constructCollectionType(List.class, FundInfo.class));
        } catch (Exception e) {
            log.warn("[FUND-SCREENER] 解析筛选结果为 FundInfo 列表异常: error={}", e.getMessage());
        }

        String nodeId = node != null ? node.getNodeId() : "screening";
        String artifactId = "art-screen-" + UUID.randomUUID().toString().substring(0, 8);
        ArtifactMetadata metadata = ArtifactMetadata.standard("FundScreeningTool");

        EvidenceContract contract;
        if (funds == null || funds.isEmpty()) {
            contract = EvidenceContract.insufficient(List.of("candidate_count_zero"));
        } else {
            List<String> uris = funds.stream().map(f -> "fund://" + f.getFundCode()).toList();
            contract = EvidenceContract.sufficient("初筛命中 " + funds.size() + " 只标的", uris);
        }

        FundPool pool = FundPool.of(funds, "初筛命中 " + (funds != null ? funds.size() : 0) + " 只标的");
        return new Artifact<>(artifactId, ArtifactType.FUND_POOL, nodeId, pool, metadata, contract);
    }
}

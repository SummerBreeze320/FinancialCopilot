package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.llm.dto.LlmRequest;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.core.llm.service.LlmService;
import com.financial.copilot.agent.core.pipeline.ResearchBlackboard;
import com.financial.copilot.agent.core.prompt.ReportSynthesizerPrompt;
import com.financial.copilot.agent.core.skill.SkillMatcher;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * <h1>投研报告生成主编 Agent (Report Synthesizer)</h1>
 * <p>
 * 职责：作为多智能体复合投研流水线的首席主编（CIO 角色），整合黑板中沉淀的各阶段客观事实底座
 * （标的初筛池、多维量化评分、决赛圈对标、底层持仓与季报观点文本），结合适格投资者画像与历史提纯事实，
 * 严格遵循 Tool-as-Truth 纪律，生成专业、客观、严谨的结构化投研深度 Markdown 报告。
 * 支持同步阻塞输出与 SSE 响应式流式输出，支持双模型路由选择。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ReportSynthesizer {

    /**
     * 大模型统一服务
     */
    private final LlmService clientService;

    /**
     * Skill 技能动态匹配器
     */
    private final SkillMatcher skillMatcher;

    /**
     * 构造函数，注入大模型统一服务
     *
     * @param clientService 大模型调用服务
     */
    public ReportSynthesizer(LlmService clientService) {
        this(clientService, null);
    }

    public ReportSynthesizer(LlmService clientService, SkillMatcher skillMatcher) {
        this.clientService = clientService;
        this.skillMatcher = skillMatcher;
    }

    /**
     * 同步生成完整报告文本（默认极速标准投研模式）
     *
     * @param factualContext 事实上下文（由各 Agent 收集并汇总于黑板中的客观数据）
     * @param userGoal       用户原始研究诉求
     * @return 深度 Markdown 研报文本
     */
    public String synthesize(String factualContext, String userGoal) {
        return synthesize(factualContext, userGoal, false);
    }

    /**
     * 同步生成完整报告文本（支持指定是否开启深度思考推理模式）
     *
     * @param factualContext 事实上下文
     * @param userGoal       用户原始研究诉求
     * @param enableThinking 是否开启深度思考模式 (true 路由至 reasoning_model)
     * @return 深度 Markdown 研报文本
     */
    public String synthesize(String factualContext, String userGoal, boolean enableThinking) {
        return synthesize(factualContext, userGoal, enableThinking, null);
    }

    public String synthesize(String factualContext, String userGoal, boolean enableThinking, Consumer<LlmResponse> usageConsumer) {
        return synthesize(factualContext, userGoal, enableThinking, null, null, null, usageConsumer);
    }

    public String synthesize(String factualContext,
                              String userGoal,
                              boolean enableThinking,
                              UserInvestmentProfile profile,
                              List<String> refinedFacts,
                              List<String> pastEntries,
                              Consumer<LlmResponse> usageConsumer) {
        String skillRules = (skillMatcher != null) ? skillMatcher.matchSkillInstructions("SYNTHESIS", userGoal) : "";
        String userProfileText = formatUserProfile(profile);
        String memoryContext = formatMemoryContext(refinedFacts, pastEntries);

        var spec = ReportSynthesizerPrompt.buildSpec(
                userGoal, userProfileText, memoryContext, factualContext, skillRules);
        LlmRequest request = spec.toLlmRequest(LlmSettingsDTO.builder().enableThinking(enableThinking).build());
        request.setUsageConsumer(usageConsumer);
        return clientService.chat(request);
    }

    /**
     * 响应式流式生成报告文本 (SSE 打字机输出，默认极速标准投研模式)
     *
     * @param factualContext 事实上下文
     * @param userGoal       用户原始研究诉求
     * @return 响应式 Token 片段 Flux
     */
    public Flux<String> synthesizeStream(String factualContext, String userGoal) {
        return synthesizeStream(factualContext, userGoal, false);
    }

    /**
     * 响应式流式生成报告文本 (支持指定是否开启深度思考推理模式)
     *
     * @param factualContext 事实上下文
     * @param userGoal       用户原始研究诉求
     * @param enableThinking 是否开启深度思考模式 (true 路由至 reasoning_model)
     * @return 响应式 Token 片段 Flux
     */
    public Flux<String> synthesizeStream(String factualContext, String userGoal, boolean enableThinking) {
        return synthesizeStream(factualContext, userGoal, enableThinking, null);
    }

    public Flux<String> synthesizeStream(String factualContext, String userGoal, boolean enableThinking, Consumer<LlmResponse> usageConsumer) {
        return synthesizeStream(factualContext, userGoal, enableThinking, null, null, null, usageConsumer);
    }

    public Flux<String> synthesizeStream(String factualContext,
                                          String userGoal,
                                          boolean enableThinking,
                                          UserInvestmentProfile profile,
                                          List<String> refinedFacts,
                                          List<String> pastEntries,
                                          Consumer<LlmResponse> usageConsumer) {
        String skillRules = (skillMatcher != null) ? skillMatcher.matchSkillInstructions("SYNTHESIS", userGoal) : "";
        String userProfileText = formatUserProfile(profile);
        String memoryContext = formatMemoryContext(refinedFacts, pastEntries);

        var spec = ReportSynthesizerPrompt.buildSpec(
                userGoal, userProfileText, memoryContext, factualContext, skillRules);
        LlmRequest request = spec.toLlmRequest(LlmSettingsDTO.builder().enableThinking(enableThinking).build());
        request.setUsageConsumer(usageConsumer);
        return clientService.chatStream(request);
    }

    private String formatUserProfile(UserInvestmentProfile profile) {
        if (profile == null) return null;
        StringBuilder sb = new StringBuilder();
        if (profile.getRiskToleranceLevel() != null) {
            sb.append("风险承受能力等级: ").append(profile.getRiskToleranceLevel()).append("\n");
        }
        if (profile.getInvestmentHorizon() != null) {
            sb.append("投资期限偏好: ").append(profile.getInvestmentHorizon()).append("\n");
        }
        if (profile.getTargetAnnualReturn() != null) {
            sb.append("预期目标年化收益率: ").append(profile.getTargetAnnualReturn()).append("%\n");
        }
        if (profile.getMaxDrawdownTolerance() != null) {
            sb.append("最大回撤容忍度: ").append(profile.getMaxDrawdownTolerance()).append("%\n");
        }
        if (profile.getPreferredSectors() != null && !profile.getPreferredSectors().isEmpty()) {
            sb.append("重点偏好行业板块: ").append(String.join(", ", profile.getPreferredSectors())).append("\n");
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    private String formatMemoryContext(List<String> refinedFacts, List<String> pastEntries) {
        StringBuilder sb = new StringBuilder();
        if (refinedFacts != null && !refinedFacts.isEmpty()) {
            sb.append("【历史沉淀研报事实与用户偏好 (Refined Facts)】:\n");
            for (String rf : refinedFacts) {
                sb.append("- ").append(rf).append("\n");
            }
        }
        if (pastEntries != null && !pastEntries.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("【前序流水线执行记录】:\n");
            for (String pe : pastEntries) {
                sb.append("- ").append(pe).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    /**
     * 强类型 DAG 终审合成节点执行入口
     *
     * @param node            当前 DAG 节点
     * @param store           产物存储总线
     * @param userGoal        用户原始诉求
     * @param enableThinking  是否开启思考模型
     * @param profile         用户投资画像
     * @param refinedFacts    长期记忆精炼事实
     * @param pastEntries     前序执行记录
     * @param usageConsumer   Token 计量回调
     * @return 强类型最终研报产物
     */
    public Artifact<FinalSynthesisReport> synthesizeArtifact(
            GraphNode node,
            ArtifactStore store,
            String userGoal,
            boolean enableThinking,
            UserInvestmentProfile profile,
            List<String> refinedFacts,
            List<String> pastEntries,
            Consumer<LlmResponse> usageConsumer
    ) {
        String nodeId = node != null ? node.getNodeId() : "synthesis";

        // 1. 从 ArtifactStore 汇总全部事实上下文
        String factualContext = buildFactualContextFromStore(store);

        // 2. 调度模型生成研报全文
        String markdownReport = synthesize(factualContext, userGoal, enableThinking, profile, refinedFacts, pastEntries, usageConsumer);

        // 3. 构造强类型终审报告与可审计证据契约
        String artifactId = "art-report-" + UUID.randomUUID().toString().substring(0, 8);
        ArtifactMetadata metadata = ArtifactMetadata.standard("ReportSynthesizer");

        List<String> evidenceUris = new ArrayList<>();
        if (store != null) {
            for (Artifact<?> art : store.getAllArtifacts().values()) {
                if (art.evidenceContract() != null && art.evidenceContract().evidenceUris() != null) {
                    evidenceUris.addAll(art.evidenceContract().evidenceUris());
                }
            }
        }

        EvidenceContract contract = EvidenceContract.sufficient(
                "全流程研报终审合成完毕", evidenceUris.stream().distinct().toList());

        FinalSynthesisReport reportPayload = FinalSynthesisReport.of(
                "专业基金投资配置研报",
                "全流程投研流水线合成建议",
                markdownReport,
                Map.of("权益类公募基金", 0.60, "固收稳健类资产", 0.40),
                evidenceUris.stream().filter(u -> u.startsWith("fund://")).map(u -> u.replace("fund://", "")).distinct().toList()
        );

        return new Artifact<>(
                artifactId,
                ArtifactType.FINAL_REPORT,
                nodeId,
                reportPayload,
                metadata,
                contract
        );
    }

    /**
     * 强类型 DAG 终审合成节点执行入口（极速简洁版）
     *
     * @param node     当前 DAG 节点
     * @param store    产物存储总线
     * @param userGoal 用户原始诉求
     * @return 强类型最终研报产物
     */
    public Artifact<FinalSynthesisReport> synthesizeArtifact(
            GraphNode node,
            ArtifactStore store,
            String userGoal
    ) {
        return synthesizeArtifact(node, store, userGoal, false, null, null, null, null);
    }

    /**
     * 从产物存储总线提炼各上游节点产生的事实上下文
     *
     * @param store 产物总线
     * @return 事实上下文文本
     */
    private String buildFactualContextFromStore(ArtifactStore store) {
        if (store == null) return "暂无上游事实数据";
        StringBuilder sb = new StringBuilder();
        sb.append("=== 流水线全景事实总览 (ArtifactStore) ===\n\n");

        // 1. 初筛产物 (FundPool)
        Optional<Artifact<FundPool>> poolOpt = store.findFirstByType(ArtifactType.FUND_POOL);
        if (poolOpt.isPresent() && poolOpt.get().payload() instanceof FundPool pool) {
            sb.append("【阶段 1 筛选命中候选标的池 (共 ").append(pool.totalCount()).append(" 只)】:\n");
            if (pool.funds() != null && !pool.funds().isEmpty()) {
                for (FundInfo f : pool.funds()) {
                    sb.append("- ").append(f.getFundCode()).append(" ").append(f.getFundName()).append("\n");
                }
            } else if (pool.fundCodes() != null) {
                for (String c : pool.fundCodes()) {
                    sb.append("- ").append(c).append("\n");
                }
            }
            sb.append("\n");
        }

        // 2. 深度分析产物 (FundResearchResult)
        Optional<Artifact<FundResearchResult>> researchOpt = store.findFirstByType(ArtifactType.FUND_RESEARCH);
        if (researchOpt.isPresent() && researchOpt.get().payload() instanceof FundResearchResult res) {
            sb.append("【阶段 2 基金经理综合能力量化评分排名】:\n");
            if (res.evaluatedFunds() != null) {
                for (Map<String, Object> item : res.evaluatedFunds()) {
                    sb.append("- 标的: ").append(item.get("fundCode"))
                      .append(", 综合得分: ").append(item.get("score"))
                      .append("\n");
                }
            }
            if (res.topCandidates() != null && !res.topCandidates().isEmpty()) {
                sb.append("- 胜出决赛圈候选: ").append(res.topCandidates()).append("\n");
            }
            sb.append("\n");
        }

        // 3. 对标产物 (ComparisonReport)
        Optional<Artifact<ComparisonReport>> compOpt = store.findFirstByType(ArtifactType.COMPARISON_REPORT);
        if (compOpt.isPresent() && compOpt.get().payload() instanceof ComparisonReport comp) {
            sb.append("【阶段 3 决赛圈最优标的横向对标与归因事实】:\n");
            sb.append(comp.comparisonAnalysis()).append("\n");
            if (comp.sharedHoldings() != null && !comp.sharedHoldings().isEmpty()) {
                sb.append("- 知识图谱重合持仓股: ").append(comp.sharedHoldings()).append("\n");
            }
            sb.append("\n");
        }

        // 4. 兜底回退：如果 store 中尚未沉淀 typed record，但包含 ResearchBlackboard
        if (sb.length() <= 45) {
            Object bb = store.getGlobalContext("blackboard");
            if (bb instanceof ResearchBlackboard blackboard) {
                if (blackboard.getCandidateFunds() != null && !blackboard.getCandidateFunds().isEmpty()) {
                    sb.append("【候选标的池】: ").append(blackboard.getCandidateFunds().size()).append(" 只\n");
                }
                if (blackboard.getTopCandidates() != null) {
                    sb.append("【决赛标的】: ").append(blackboard.getTopCandidates()).append("\n");
                }
                if (blackboard.getRaw(ResearchBlackboard.KEY_COMPARISON_FACTS) != null) {
                    sb.append("【对标事实】: ").append(blackboard.getRaw(ResearchBlackboard.KEY_COMPARISON_FACTS)).append("\n");
                }
            }
        }

        return sb.toString();
    }
}

package com.financial.copilot.agent.core.dag.adapter;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.pipeline.ResearchBlackboard;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import com.financial.copilot.domain.user.enums.RiskToleranceLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：BlackboardAdapter
 */
class BlackboardAdapterTest {

    @Test
    @DisplayName("测试全局上下文双向同步")
    void testSyncGlobalContext() {
        ResearchBlackboard blackboard = new ResearchBlackboard();
        UserInvestmentProfile profile = UserInvestmentProfile.builder()
                .riskToleranceLevel(RiskToleranceLevel.C4)
                .build();
        blackboard.setUserInvestmentProfile(profile);
        blackboard.setEnableThinking(true);
        blackboard.put("sessionId", "session-123");

        ArtifactStore store = new ArtifactStore();
        BlackboardAdapter.copyGlobalContext(blackboard, store);

        assertEquals(profile, store.getGlobalContext(ResearchBlackboard.KEY_USER_INVESTMENT_PROFILE));
        assertEquals(true, store.getGlobalContext(ResearchBlackboard.KEY_ENABLE_THINKING));
        assertEquals("session-123", store.getGlobalContext("sessionId"));

        // 反向同步到新黑板
        ResearchBlackboard newBlackboard = new ResearchBlackboard();
        BlackboardAdapter.copyGlobalContext(store, newBlackboard);

        assertEquals(profile, newBlackboard.getUserInvestmentProfile());
        assertTrue(newBlackboard.isEnableThinking());
        assertEquals("session-123", newBlackboard.getRaw("sessionId"));
    }

    @Test
    @DisplayName("测试从黑板提取产物并写回黑板")
    void testExtractAndApplyArtifacts() {
        ResearchBlackboard blackboard = new ResearchBlackboard();
        List<FundInfo> funds = List.of(
                FundInfo.builder().fundCode("003095").fundName("中欧医疗健康混合A").build(),
                FundInfo.builder().fundCode("005827").fundName("易方达蓝筹精选混合").build()
        );
        blackboard.put(ResearchBlackboard.KEY_CANDIDATE_FUNDS, funds);

        // 1. SCREENING 产物提取
        Artifact<?> fundPoolArtifact = BlackboardAdapter.extractArtifactFromBlackboard("step-1", "SCREENING", blackboard);
        assertNotNull(fundPoolArtifact);
        assertEquals(ArtifactType.FUND_POOL, fundPoolArtifact.type());
        assertEquals("step-1", fundPoolArtifact.producerNodeId());

        // 2. 将产物写回到全新的黑板
        ResearchBlackboard targetBlackboard = new ResearchBlackboard();
        BlackboardAdapter.applyArtifactToBlackboard(fundPoolArtifact, targetBlackboard);

        List<FundInfo> restoredFunds = targetBlackboard.getCandidateFunds();
        assertNotNull(restoredFunds);
        assertEquals(2, restoredFunds.size());
        assertEquals("003095", restoredFunds.get(0).getFundCode());

        // 3. COMPARISON 产物提取与写回
        blackboard.put(ResearchBlackboard.KEY_COMPARISON_FACTS, "对标事实文本：A比B收益高，回撤略大");
        Artifact<?> compArtifact = BlackboardAdapter.extractArtifactFromBlackboard("step-3", "COMPARISON", blackboard);
        assertEquals(ArtifactType.COMPARISON_REPORT, compArtifact.type());

        BlackboardAdapter.applyArtifactToBlackboard(compArtifact, targetBlackboard);
        assertEquals("对标事实文本：A比B收益高，回撤略大", targetBlackboard.getRaw(ResearchBlackboard.KEY_COMPARISON_FACTS));

        // 4. SYNTHESIS 最终报告
        blackboard.put(ResearchBlackboard.KEY_FINAL_REPORT, "# 投研报告最终版");
        Artifact<?> finalReportArt = BlackboardAdapter.extractArtifactFromBlackboard("step-4", "SYNTHESIS", blackboard);
        assertEquals(ArtifactType.FINAL_REPORT, finalReportArt.type());

        BlackboardAdapter.applyArtifactToBlackboard(finalReportArt, targetBlackboard);
        assertEquals("# 投研报告最终版", targetBlackboard.getFinalReport());
    }
}

package com.financial.copilot.agent.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.config.LlmProperties;
import com.financial.copilot.agent.core.llm.factory.LlmDynamicWebClientFactory;
import com.financial.copilot.agent.core.llm.provider.LlmProviderRegistry;
import com.financial.copilot.agent.core.llm.service.DefaultLlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>任务拆解规划器单元测试 (Task Decomposer Test)</h1>
 * <p>
 * 测试验证 {@link TaskDecomposer} 能否准确识别复合投研意图，
 * 并将其精准拆解为结构化四阶段 DAG 执行计划 {@link ExecutionPlan}。
 * </p>
 *
 * @author FinancialCopilot
 */
class TaskDecomposerTest {

    private TaskDecomposer taskDecomposer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("placeholder-test-key");
        LlmConfigManager configManager = new LlmConfigManager(properties);
        configManager.init();
        LlmProviderRegistry providerRegistry = new LlmProviderRegistry();
        LlmDynamicWebClientFactory webClientFactory = new LlmDynamicWebClientFactory();
        DefaultLlmService llmService = new DefaultLlmService(webClientFactory, configManager, providerRegistry, objectMapper);
        taskDecomposer = new TaskDecomposer(llmService, objectMapper);
    }

    /**
     * 测试验证复合投研指令被正确解构为4阶段执行计划
     */
    @Test
    @DisplayName("验证复合投研指令被正确解构为4阶段执行计划")
    void testDecomposeComplexPrompt() {
        String complexQuery = "帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议。";

        ExecutionPlan plan = taskDecomposer.decompose(complexQuery);

        assertNotNull(plan);
        assertTrue(plan.isComplex(), "多步复合指令应标记为 isComplex=true");
        assertEquals(4, plan.getSteps().size(), "应解构出4个步骤");

        SubTask step1 = plan.getSteps().get(0);
        assertEquals(1, step1.getStepIndex());
        assertEquals("SCREENING", step1.getTaskType());

        SubTask step2 = plan.getSteps().get(1);
        assertEquals(2, step2.getStepIndex());
        assertEquals("BATCH_ANALYSIS", step2.getTaskType());
        assertTrue(step2.getDependsOn().contains(1));

        SubTask step3 = plan.getSteps().get(2);
        assertEquals(3, step3.getStepIndex());
        assertEquals("COMPARISON", step3.getTaskType());
        assertTrue(step3.getDependsOn().contains(2));

        SubTask step4 = plan.getSteps().get(3);
        assertEquals(4, step4.getStepIndex());
        assertEquals("SYNTHESIS", step4.getTaskType());
        assertTrue(step4.getDependsOn().containsAll(List.of(1, 2, 3)));
    }

    /**
     * 测试验证简单对比请求退化为单步计划
     */
    @Test
    @DisplayName("验证简单对比请求退化为单步计划")
    void testDecomposeSingleIntentComparison() {
        String singleQuery = "对比易方达蓝筹与富国天惠的风格特征";

        ExecutionPlan plan = taskDecomposer.decompose(singleQuery);

        assertNotNull(plan);
        assertFalse(plan.isComplex(), "单意图指令应标记为 isComplex=false");
        assertEquals(1, plan.getSteps().size());
        assertEquals("COMPARISON", plan.getSteps().get(0).getTaskType());
    }

    /**
     * 测试验证开启深度思考模式下的解构流程
     */
    @Test
    @DisplayName("验证开启深度思考推理模式下的解构调用")
    void testDecomposeWithEnableThinking() {
        String complexQuery = "帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议。";
        ExecutionPlan plan = taskDecomposer.decompose(complexQuery, true);
        assertNotNull(plan);
        assertTrue(plan.isComplex());
        assertEquals(4, plan.getSteps().size());
    }
}

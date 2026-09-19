package com.financial.copilot.agent.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextReducerTest {

    private final ContextReducer reducer = new ContextReducer();

    @Test
    @DisplayName("测试未超出阈值时不触发压缩缩减")
    void testNoReductionWhenUnderBudget() {
        List<String> context = List.of("USER: 选基", "ASSISTANT: 推荐易方达蓝筹");
        List<String> reduced = reducer.compressAndReduce(context, 1000);

        assertThat(reduced).hasSize(2)
                .containsExactlyElementsOf(context);
    }

    @Test
    @DisplayName("测试超出阈值时生成滚动摘要并置于首位")
    void testRollingSummaryGeneratedWhenOverBudget() {
        List<String> context = List.of(
                "USER: 请帮我筛选近三年回撤小于10%的医药基金",
                "ASSISTANT: 为您筛选出工银前沿医疗与中欧医疗健康",
                "USER: 对比一下这两只基金的重仓股",
                "ASSISTANT: 工银偏重创新药，中欧偏重医疗器械与CXO",
                "USER: 换掉第二只，加入华泰柏瑞医疗进行对比"
        );

        // 阈值设为 100，使前序几轮被压缩为摘要，最新一条依然保留在尾部
        int threshold = 100;
        List<String> reduced = reducer.compressAndReduce(context, threshold);

        assertThat(reduced).isNotEmpty();
        assertThat(reduced.get(0)).startsWith("【前期历史交互摘要】");
        // 最新的一条依然被保留在末尾
        assertThat(reduced.get(reduced.size() - 1)).isEqualTo("USER: 换掉第二只，加入华泰柏瑞医疗进行对比");
    }

    @Test
    @DisplayName("测试多轮增量压缩时不会嵌套重复摘要头")
    void testNoNestedSummaryHeaders() {
        String existingSummary = """
                【前期历史交互摘要】:
                - 步骤/轮次1: USER: 第一轮选基
                - 步骤/轮次2: ASSISTANT: 推荐A基金
                """;
        List<String> context = List.of(
                existingSummary,
                "USER: 第二轮对比A和B",
                "ASSISTANT: A基金夏普更高",
                "USER: 第三轮查看A基金经理"
        );

        int threshold = 80;
        List<String> reduced = reducer.compressAndReduce(context, threshold);

        assertThat(reduced).isNotEmpty();
        String summary = reduced.get(0);
        assertThat(summary).startsWith("【前期历史交互摘要】");
        // 确保不会出现两次 【前期历史交互摘要】
        int firstIdx = summary.indexOf("【前期历史交互摘要】");
        int secondIdx = summary.indexOf("【前期历史交互摘要】", firstIdx + 1);
        assertThat(secondIdx).isEqualTo(-1);
    }
}

package com.financial.copilot.agent.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenUtilTest {

    @Test
    @DisplayName("测试空字符串与空列表的安全处理")
    void testEmptyAndNull() {
        assertThat(TokenUtil.estimateTokens((String) null)).isZero();
        assertThat(TokenUtil.estimateTokens("")).isZero();
        assertThat(TokenUtil.estimateTokens((List<String>) null)).isZero();
        assertThat(TokenUtil.estimateTokens(List.of())).isZero();
    }

    @Test
    @DisplayName("测试纯英文字符 Token 估算")
    void testEnglishTokens() {
        String ascii = "HelloWorld"; // 10 chars * 0.25 = 2.5 -> ceil = 3
        assertThat(TokenUtil.estimateTokens(ascii)).isEqualTo(3);
    }

    @Test
    @DisplayName("测试中文金融汉字 Token 估算具有更高权重")
    void testChineseTokens() {
        String chinese = "易方达蓝筹精选"; // 7 汉字 * 1.3 = 9.1 -> ceil = 10
        int tokens = TokenUtil.estimateTokens(chinese);
        assertThat(tokens).isEqualTo(10);
    }

    @Test
    @DisplayName("测试中英混合与金融标的代码 Token 估算")
    void testMixedTokens() {
        // 易方达(3*1.3=3.9) 005827(6*0.25=1.5) PE估值(2*0.25 + 2*1.3 = 0.5 + 2.6 = 3.1)
        String mixed = "易方达005827PE估值";
        int tokens = TokenUtil.estimateTokens(mixed);
        assertThat(tokens).isGreaterThan(5);
    }

    @Test
    @DisplayName("测试列表聚合估算")
    void testListEstimation() {
        List<String> list = List.of("选医药基金", "对比近三年收益率");
        int total = TokenUtil.estimateTokens(list);
        assertThat(total).isGreaterThan(10);
    }
}

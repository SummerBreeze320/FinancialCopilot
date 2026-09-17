package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * <h1>基金智能筛选只读工具 (Fund Screening Tool)</h1>
 * <p>
 * 供 ScreenerAgent 使用。支持配置外部 HTTP 选基服务，未配置或调用失败时优雅回退。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundScreeningTool {

    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    @Value("${copilot.screening.fund-url:}")
    private String fundScreeningUrl;

    public FundScreeningTool(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    @Autowired
    public FundScreeningTool(ObjectMapper objectMapper, @Autowired(required = false) WebClient webClient) {
        this.objectMapper = objectMapper;
        this.webClient = webClient != null ? webClient : WebClient.builder().build();
    }

    public String screenFunds(Map<String, Object> criteria) {
        log.info("[TOOL CALL-FUND] 执行多维选基: criteria={}", criteria);
        if (StringUtils.hasText(fundScreeningUrl)) {
            try {
                return webClient.post()
                        .uri(fundScreeningUrl)
                        .bodyValue(criteria)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("[TOOL CALL-FUND] 调用外部选基接口失败，降级回退默认候选池: {}", e.getMessage());
            }
        }
        return "[{\"fundCode\":\"005827.OF\",\"fundName\":\"易方达蓝筹精选\"},{\"fundCode\":\"161005.OF\",\"fundName\":\"富国天惠成长混合\"}]";
    }
}

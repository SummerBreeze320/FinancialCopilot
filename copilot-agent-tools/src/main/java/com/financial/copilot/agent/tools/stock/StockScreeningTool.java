package com.financial.copilot.agent.tools.stock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * <h1>股票智能筛选只读工具 (Stock Screening Tool)</h1>
 * <p>
 * 供 StockScreenerAgent 使用。支持配置外部 HTTP 选股服务，未配置或调用失败时优雅回退。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockScreeningTool {

    private final WebClient webClient;

    @Value("${copilot.screening.stock-url:}")
    private String stockScreeningUrl;

    public StockScreeningTool() {
        this(null);
    }

    @Autowired
    public StockScreeningTool(@Autowired(required = false) WebClient webClient) {
        this.webClient = webClient != null ? webClient : WebClient.builder().build();
    }

    public String screenStocks(Map<String, Object> criteria) {
        log.info("[TOOL CALL-STOCK] 正在执行股票多因子筛选: criteria={}", criteria);
        if (StringUtils.hasText(stockScreeningUrl)) {
            try {
                return webClient.post()
                        .uri(stockScreeningUrl)
                        .bodyValue(criteria)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("[TOOL CALL-STOCK] 调用外部选股接口失败，降级回退默认候选池: {}", e.getMessage());
            }
        }
        return "[{\"stockCode\":\"600519.SH\",\"stockName\":\"贵州茅台\"}]";
    }
}

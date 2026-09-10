package com.financial.copilot.agent.core.llm.factory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>大模型动态 WebClient 客户端连接池缓存工厂 (LLM Dynamic WebClient Factory)</h1>
 * <p>
 * 职责：根据请求所指定的 Base URL 与 API Key 动态构建并复用 Spring 响应式 {@link WebClient} 实例，
 * 避免频繁重复创建连接池开销，统一配置缓冲区与超时保护。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class LlmDynamicWebClientFactory {

    /**
     * 连接客户端本地线程安全缓存映射
     */
    private final Map<String, WebClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 响应式传输缓冲区大小 (16MB，支持极长投研研报与大模型大上下文传输)
     */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    /**
     * 获取或动态创建 WebClient
     *
     * @param baseUrl 端点基础路径
     * @param apiKey  鉴权 API Key（可为空，如本地 Ollama）
     * @return 预热可用的 WebClient
     */
    public WebClient getOrCreateWebClient(String baseUrl, String apiKey) {
        String cleanBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : "https://api.deepseek.com/v1";
        String cleanApiKey = (apiKey != null) ? apiKey.trim() : "";
        String cacheKey = cleanBaseUrl + "::" + cleanApiKey.hashCode();

        return clientCache.computeIfAbsent(cacheKey, key -> {
            log.info("[LLM-CLIENT-FACTORY] 动态初始化大模型连接客户端: baseUrl={}", cleanBaseUrl);

            HttpClient httpClient = HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(120));

            ExchangeStrategies strategies = ExchangeStrategies.builder()
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                    .build();

            WebClient.Builder builder = WebClient.builder()
                    .baseUrl(cleanBaseUrl)
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .exchangeStrategies(strategies)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

            if (!cleanApiKey.isBlank() && !cleanApiKey.contains("placeholder")) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cleanApiKey);
            }

            return builder.build();
        });
    }
}

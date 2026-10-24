package com.financial.copilot.gateway.router;

import lombok.Builder;

import java.time.Duration;

/**
 * <h1>Provider 端点规格描述 (ProviderEndpointSpec)</h1>
 * <p>描述路由决策产生的一个具体的模型端点配置。</p>
 *
 * @param provider 厂商/通道标识
 * @param modelName 模型名称
 * @param baseUrl 服务地址
 * @param apiKey 凭据密钥
 * @param timeout 超时时限
 * @param isLocal 是否为本地低时延端点 (如 codex-bridge)
 * @author FinancialCopilot
 */
@Builder
public record ProviderEndpointSpec(
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        Duration timeout,
        boolean isLocal
) {}

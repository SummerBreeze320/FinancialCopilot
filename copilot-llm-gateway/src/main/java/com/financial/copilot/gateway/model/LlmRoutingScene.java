package com.financial.copilot.gateway.model;

/**
 * <h1>大语言模型路由场景枚举 (LlmRoutingScene)</h1>
 * <p>定义模型网关支持的多级业务调用场景与路由优先级。</p>
 *
 * @author FinancialCopilot
 */
public enum LlmRoutingScene {
    /** 极速响应场景 (意图识别初筛、实体抽取，默认路由至本地 codex-bridge flash-low) */
    FAST,
    /** 标准对话分析场景 (基金体检、研报初稿、多轮交互) */
    STANDARD,
    /** 深度推理思考场景 (深度归因、多标的横向比对、资产配置，路由至 reasoning 模型) */
    REASONING,
    /** 量化与代码分析场景 (Python 回测校验、公式生成) */
    QUANT,
    /** 兜底降级场景 (主链路熔断或网络异常时的保底链路) */
    FALLBACK
}

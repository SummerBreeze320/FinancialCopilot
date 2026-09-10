package com.financial.copilot.agent.core.llm.service;

import com.financial.copilot.agent.core.llm.dto.LlmConnectionTestRequest;
import com.financial.copilot.agent.core.llm.dto.LlmConnectionTestResult;
import com.financial.copilot.agent.core.llm.dto.LlmRequest;
import com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel;
import reactor.core.publisher.Flux;

/**
 * <h1>多厂商大模型统一调用服务契约 (Universal LLM Service Interface)</h1>
 * <p>
 * 职责：作为全工程大模型交互的唯一核心契约，屏蔽底层厂商具体 API 协议差异，
 * 支持同步非流式推理、反应式 SSE 流式推送以及连通性探测。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface LlmService {

    /**
     * 结构化同步推理调用
     *
     * @param request 包含提示词与动态调优设置的请求体
     * @return 大模型返回正文内容
     */
    String chat(LlmRequest request);

    /**
     * 便捷同步推理调用（使用当前系统默认活动模型）
     *
     * @param systemPrompt 系统角色提示词
     * @param userMessage  用户提问或事实上下文
     * @return 大模型返回正文内容
     */
    default String chat(String systemPrompt, String userMessage) {
        return chat(LlmRequest.of(systemPrompt, userMessage));
    }

    /**
     * 便捷同步推理调用（指定客户投研深度档位）
     *
     * @param systemPrompt 系统角色提示词
     * @param userMessage  用户提问或事实上下文
     * @param level        投研深度与思考档位 (HIGH / MIDDLE / LOW)
     * @return 大模型返回正文内容
     */
    String chat(String systemPrompt, String userMessage, LlmPerformanceLevel level);

    /**
     * 响应式流式推理 (SSE Token 实时流)
     *
     * @param request 包含提示词与动态设置的请求体
     * @return 响应式 Token 文本块 Flux 流
     */
    Flux<String> chatStream(LlmRequest request);

    /**
     * 便捷响应式流式推理（使用当前系统默认活动模型）
     *
     * @param systemPrompt 系统角色提示词
     * @param userMessage  用户提问或事实上下文
     * @return 响应式 Token 文本块 Flux 流
     */
    default Flux<String> chatStream(String systemPrompt, String userMessage) {
        return chatStream(LlmRequest.of(systemPrompt, userMessage));
    }

    /**
     * 便捷响应式流式推理（指定客户投研深度档位）
     *
     * @param systemPrompt 系统角色提示词
     * @param userMessage  用户提问或事实上下文
     * @param level        投研深度与思考档位 (HIGH / MIDDLE / LOW)
     * @return 响应式 Token 文本块 Flux 流
     */
    Flux<String> chatStream(String systemPrompt, String userMessage, LlmPerformanceLevel level);

    /**
     * 研发测试专用：探测指定厂商端点与模型的连通性与网络延迟
     *
     * @param testRequest 探测参数
     * @return 连通性测试报告
     */
    LlmConnectionTestResult testConnection(LlmConnectionTestRequest testRequest);
}

package com.financial.copilot.agent.core.infra.llm;

import com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * <h1>受治理的 Spring AI 模型装饰器 (GovernedChatModel)</h1>
 * <p>
 * 统一作为 Supervisor 及所有专业 Subagent 的模型执行基座。
 * 强制所有模型调用均须经过全局并发限制器与许可管理，支持同步调用与反应式流式调用，
 * 确保在异常、超时或客户端断开时可靠释放并发槽位。
 * </p>
 *
 * @author FinancialCopilot
 */
public class GovernedChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GovernedChatModel.class);

    private final ChatModel delegate;
    private final LlmCapacityLimiter limiter;

    public GovernedChatModel(ChatModelDelegateFactory factory, LlmCapacityLimiter limiter) {
        this(factory.getOrCreateDefault(), limiter);
    }

    public GovernedChatModel(ChatModel delegate, LlmCapacityLimiter limiter) {
        this.delegate = Objects.requireNonNull(delegate, "delegate ChatModel cannot be null");
        this.limiter = Objects.requireNonNull(limiter, "limiter cannot be null");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt cannot be null");
        try (LlmCapacityLimiter.Permit ignored = limiter.acquire()) {
            return delegate.call(prompt);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt cannot be null");
        return Flux.usingWhen(
                limiter.acquireReactive(),
                permit -> delegate.stream(prompt),
                permit -> Mono.fromRunnable(permit::close),
                (permit, error) -> Mono.fromRunnable(permit::close),
                permit -> Mono.fromRunnable(permit::close)
        );
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    public ChatModel getDelegate() {
        return delegate;
    }
}

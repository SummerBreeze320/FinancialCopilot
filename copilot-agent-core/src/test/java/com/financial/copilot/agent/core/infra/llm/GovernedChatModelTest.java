package com.financial.copilot.agent.core.infra.llm;

import com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernedChatModelTest {

    @Mock
    private ChatModel delegate;

    private LlmCapacityLimiter limiter;
    private GovernedChatModel governedChatModel;

    @BeforeEach
    void setUp() {
        limiter = new LlmCapacityLimiter(2);
        governedChatModel = new GovernedChatModel(delegate, limiter);
    }

    @Test
    void callAcquiresAndReleasesPermit() {
        Prompt prompt = new Prompt("分析基金 000001");
        ChatResponse expectedResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("分析结果正常"))));

        when(delegate.call(prompt)).thenAnswer(inv -> {
            assertThat(limiter.availablePermits()).isEqualTo(1);
            return expectedResponse;
        });

        assertThat(limiter.availablePermits()).isEqualTo(2);
        ChatResponse actual = governedChatModel.call(prompt);
        assertThat(actual).isSameAs(expectedResponse);
        assertThat(limiter.availablePermits()).isEqualTo(2);

        verify(delegate).call(prompt);
    }

    @Test
    void callReleasesPermitOnException() {
        Prompt prompt = new Prompt("触发异常");
        when(delegate.call(prompt)).thenThrow(new RuntimeException("模型连接失败"));

        assertThat(limiter.availablePermits()).isEqualTo(2);
        assertThatThrownBy(() -> governedChatModel.call(prompt))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模型连接失败");

        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    @Test
    void streamAcquiresAndReleasesPermit() {
        Prompt prompt = new Prompt("流式分析");
        ChatResponse chunk1 = new ChatResponse(List.of(new Generation(new AssistantMessage("片段一"))));
        ChatResponse chunk2 = new ChatResponse(List.of(new Generation(new AssistantMessage("片段二"))));

        when(delegate.stream(prompt)).thenReturn(Flux.just(chunk1, chunk2));

        assertThat(limiter.availablePermits()).isEqualTo(2);

        StepVerifier.create(governedChatModel.stream(prompt))
                .expectNext(chunk1)
                .expectNext(chunk2)
                .verifyComplete();

        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    @Test
    void streamReleasesPermitOnError() {
        Prompt prompt = new Prompt("流式异常");
        when(delegate.stream(prompt)).thenReturn(Flux.error(new IllegalStateException("流式连接中断")));

        assertThat(limiter.availablePermits()).isEqualTo(2);

        StepVerifier.create(governedChatModel.stream(prompt))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    @Test
    void delegateFactoryCreatesLocalCodexBridgeModel() {
        ChatModelDelegateFactory factory = new ChatModelDelegateFactory();
        ChatModel model1 = factory.getOrCreateDefault();
        ChatModel model2 = factory.getOrCreateDefault();

        assertThat(model1).isNotNull();
        assertThat(model2).isSameAs(model1);
    }
}

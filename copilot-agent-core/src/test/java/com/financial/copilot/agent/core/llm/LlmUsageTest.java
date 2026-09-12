package com.financial.copilot.agent.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.llm.config.*;
import com.financial.copilot.agent.core.llm.dto.*;
import com.financial.copilot.agent.core.llm.factory.LlmDynamicWebClientFactory;
import com.financial.copilot.agent.core.llm.provider.*;
import com.financial.copilot.agent.core.llm.service.DefaultLlmService;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class LlmUsageTest {
    private DefaultLlmService service(String body, String contentType, boolean mockMode) {
        var properties = new LlmProperties();
        properties.setDefaultProvider(LlmProviderType.DEEPSEEK);
        properties.setDefaultModel("deepseek-chat");
        properties.setApiKey(mockMode ? "sk-placeholder" : "test-key");
        properties.setBaseUrl("https://example.com");
        var manager = new LlmConfigManager(properties);
        manager.init();
        var factory = mock(LlmDynamicWebClientFactory.class);
        var client = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", contentType).body(body).build())).build();
        when(factory.getOrCreateWebClient(anyString(), anyString())).thenReturn(client);
        return new DefaultLlmService(factory, manager, new LlmProviderRegistry(), new ObjectMapper());
    }

    @Test
    void synchronousResponseReportsActualTokensAndResponseModel() {
        var usages = new ArrayList<LlmResponse>();
        var request = LlmRequest.of("system", "prompt");
        request.setUsageConsumer(usages::add);
        var llm = service("{\"model\":\"actual-model\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":13,\"completion_tokens\":7}}", "application/json", false);
        assertEquals("ok", llm.chat(request));
        assertEquals(1, usages.size());
        assertEquals(13, usages.get(0).getPromptTokens());
        assertEquals(7, usages.get(0).getCompletionTokens());
        assertEquals("actual-model", usages.get(0).getModel());
    }

    @Test
    void streamingUsageOnlyEventIsCollectedOnce() {
        var usages = new ArrayList<LlmResponse>();
        var request = LlmRequest.of("system", "prompt");
        request.setUsageConsumer(usages::add);
        String usage = "data: {\"model\":\"actual-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":8}}\n\n";
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n" + usage + usage + "data: [DONE]\n\n";
        assertEquals(List.of("hello"), service(stream, "text/event-stream", false).chatStream(request).collectList().block());
        assertEquals(1, usages.size());
        assertEquals(15, usages.get(0).getPromptTokens());
        assertEquals(8, usages.get(0).getCompletionTokens());
    }

    @Test
    void missingUsageAndMockOutputNeverInventABill() {
        var usages = new ArrayList<LlmResponse>();
        var request = LlmRequest.of("system", "prompt");
        request.setUsageConsumer(usages::add);
        assertThrows(IllegalStateException.class, () -> service("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                "application/json", false).chat(request));
        service("", "application/json", true).chat(request);
        assertTrue(usages.isEmpty());
    }
    @Test
    void billingFailurePreservesItsTypeAndFractionalUsageIsRejected() {
        var request = LlmRequest.of("system", "prompt");
        var failure = new com.financial.copilot.common.exception.WalletInsufficientException(1L, 0L, 1L);
        request.setUsageConsumer(usage -> { throw failure; });
        String body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
        assertSame(failure, assertThrows(com.financial.copilot.common.exception.WalletInsufficientException.class,
                () -> service(body, "application/json", false).chat(request)));
        request.setUsageConsumer(usage -> fail("Fractional tokens must not be billed"));
        assertThrows(IllegalStateException.class, () -> service(body.replace("tokens\":1", "tokens\":1.5"),
                "application/json", false).chat(request));
    }
}

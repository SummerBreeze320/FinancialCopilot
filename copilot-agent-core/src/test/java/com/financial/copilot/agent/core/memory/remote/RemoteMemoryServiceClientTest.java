package com.financial.copilot.agent.core.memory.remote;

import com.financial.copilot.agent.core.memory.remote.dto.MemoryBundleDTO;
import com.financial.copilot.agent.core.memory.remote.dto.ProcessSessionRequestDTO;
import com.financial.copilot.agent.core.memory.remote.dto.RecallRequestDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>远程长期记忆客户端单元测试</h1>
 */
class RemoteMemoryServiceClientTest {

    @Test
    @DisplayName("当配置未启用时，recall 应直接返回 Optional.empty()")
    void testRecallWhenDisabled() {
        MemoryServiceProperties props = new MemoryServiceProperties();
        props.setEnabled(false);

        RemoteMemoryServiceClient client = new RemoteMemoryServiceClient(props, WebClient.builder());

        RecallRequestDTO req = RecallRequestDTO.builder()
                .userId("u123")
                .queryText("测试偏好")
                .build();

        Optional<MemoryBundleDTO> result = client.recall(req);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("当远程微服务正常响应时，成功反序列化并解析 MemoryBundleDTO")
    void testRecallSuccess() {
        MemoryServiceProperties props = new MemoryServiceProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:8000");
        props.setTimeoutMs(500);

        String jsonResponse = """
                {
                    "compact_context": "用户偏好新能源，风险承受能力中等",
                    "recalled_items": [
                        {
                            "memory_id": "mem-001",
                            "version": 1,
                            "content": "偏好新能源板块",
                            "compact_text": "偏好新能源",
                            "token_count": 15
                        }
                    ],
                    "total_tokens": 25,
                    "token_budget": 500
                }
                """;

        ExchangeFunction exchangeFunction = request -> {
            assertThat(request.url().getPath()).isEqualTo("/api/v1/memory/recall");
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(jsonResponse)
                    .build());
        };

        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        RemoteMemoryServiceClient client = new RemoteMemoryServiceClient(props, webClient);

        RecallRequestDTO req = RecallRequestDTO.builder()
                .userId("u123")
                .queryText("新能源基金")
                .build();

        Optional<MemoryBundleDTO> result = client.recall(req);
        assertThat(result).isPresent();

        MemoryBundleDTO bundle = result.get();
        assertThat(bundle.getCompactContext()).contains("用户偏好新能源");
        assertThat(bundle.getRecalledItems()).hasSize(1);
        assertThat(bundle.getRecalledItems().get(0).getContent()).isEqualTo("偏好新能源板块");
        assertThat(bundle.getTotalTokens()).isEqualTo(25);
    }

    @Test
    @DisplayName("当远程微服务报错 500 或异常时，静默兜底返回 Optional.empty()")
    void testRecallErrorFallback() {
        MemoryServiceProperties props = new MemoryServiceProperties();
        props.setEnabled(true);
        props.setTimeoutMs(200);

        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Internal Server Error")
                .build());

        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        RemoteMemoryServiceClient client = new RemoteMemoryServiceClient(props, webClient);

        RecallRequestDTO req = RecallRequestDTO.builder()
                .userId("u123")
                .queryText("异常测试")
                .build();

        Optional<MemoryBundleDTO> result = client.recall(req);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("当远程微服务响应超时时，静默降级返回 Optional.empty()")
    void testRecallTimeoutFallback() {
        MemoryServiceProperties props = new MemoryServiceProperties();
        props.setEnabled(true);
        props.setTimeoutMs(50); // 50ms 超时

        ExchangeFunction exchangeFunction = request -> Mono.delay(Duration.ofMillis(200))
                .map(i -> ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{}")
                        .build());

        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        RemoteMemoryServiceClient client = new RemoteMemoryServiceClient(props, webClient);

        RecallRequestDTO req = RecallRequestDTO.builder()
                .userId("u123")
                .queryText("超时测试")
                .build();

        Optional<MemoryBundleDTO> result = client.recall(req);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("测试 processSessionAsync fire-and-forget 异步投递")
    void testProcessSessionAsync() {
        MemoryServiceProperties props = new MemoryServiceProperties();
        props.setEnabled(true);

        ExchangeFunction exchangeFunction = request -> {
            assertThat(request.url().getPath()).isEqualTo("/api/v1/memory/process-session");
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"status\": \"ok\", \"extracted\": 2}")
                    .build());
        };

        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        RemoteMemoryServiceClient client = new RemoteMemoryServiceClient(props, webClient);

        ProcessSessionRequestDTO req = ProcessSessionRequestDTO.builder()
                .sessionId("session-test-01")
                .userId("u123")
                .sessionData(Map.of("key", "val"))
                .build();

        // 验证不会抛出异常
        assertDoesNotThrow(() -> client.processSessionAsync(req));
    }
}

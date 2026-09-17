package com.financial.copilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.agent.tools.configured.client.ComponentDataClient;
import com.financial.copilot.common.result.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkspaceComponentControllerTest {

    private ComponentDataClient componentDataClient;
    private ObjectMapper objectMapper;
    private WorkspaceComponentController controller;

    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            UserPrincipal.builder().userId(100L).username("testuser").build(),
            null,
            List.of()
    );

    @BeforeEach
    void setUp() {
        componentDataClient = mock(ComponentDataClient.class);
        objectMapper = new ObjectMapper();
        controller = new WorkspaceComponentController(componentDataClient, objectMapper);
    }

    @Test
    void unauthenticatedUserThrowsUnauthorized() {
        assertThatThrownBy(() -> controller.getComponentContent("comp-123", "ctx-1").block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void authenticatedUserReturnsParsedJsonContent() {
        when(componentDataClient.read("ctx-1", "comp-123"))
                .thenReturn("{\"metric\":\"SharpeRatio\",\"value\":1.85}");

        ApiResult<Map<String, Object>> result = controller.getComponentContent("comp-123", "ctx-1")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().get("refId")).isEqualTo("comp-123");
        assertThat(result.getData().get("contextId")).isEqualTo("ctx-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) result.getData().get("content");
        assertThat(content).containsEntry("metric", "SharpeRatio");
        assertThat(content).containsEntry("value", 1.85);

        verify(componentDataClient).read("ctx-1", "comp-123");
    }

    @Test
    void authenticatedUserReturnsRawStringWhenNotJson() {
        when(componentDataClient.read(null, "comp-raw"))
                .thenReturn("<html><body>Table Data</body></html>");

        ApiResult<Map<String, Object>> result = controller.getComponentContent("comp-raw", null)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("refId")).isEqualTo("comp-raw");
        assertThat(result.getData().get("contextId")).isEqualTo("");
        assertThat(result.getData().get("content")).isEqualTo("<html><body>Table Data</body></html>");
    }

    @Test
    void authenticatedUserReturnsEmptyMapWhenContentEmpty() {
        when(componentDataClient.read(null, "comp-empty"))
                .thenReturn("");

        ApiResult<Map<String, Object>> result = controller.getComponentContent("comp-empty", null)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("content")).isEqualTo(Map.of());
    }
}

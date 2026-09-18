package com.financial.copilot.agent.tools.client;

import com.financial.copilot.agent.tools.registry.ToolProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FundInfraWeb 客户端网关测试")
class FundInfraWebClientTest {

    private FundInfraWebClient infraWebClient;
    private ToolProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ToolProperties();
        infraWebClient = new FundInfraWebClient(properties, WebClient.builder());
    }

    @Test
    @DisplayName("测试未配置 URL 时安全返回 null，不抛出异常破坏主业务流")
    void testSafeFallbackWhenUrlNotConfigured() {
        properties.getInfraWeb().setServiceUrl("");
        Object value = infraWebClient.invokeValue("MFCP.Report16Picker2.GetData", Map.of("windCodes", "005827.OF"), "test-session");
        assertNull(value);
    }

    @Test
    @DisplayName("测试空 invokeName 时的参数校验")
    void testEmptyInvokeNameThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
                infraWebClient.invokeResult("", Map.of(), "session-1"));
    }
}

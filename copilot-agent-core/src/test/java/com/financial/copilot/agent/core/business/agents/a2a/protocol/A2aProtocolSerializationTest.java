package com.financial.copilot.agent.core.business.agents.a2a.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("A2A JSON-RPC 协议序列化测试")
class A2aProtocolSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("验证 A2aJsonRpcRequest 与 A2aTaskEvent 序列化反序列化符合规范")
    void testSerialization() throws Exception {
        A2aJsonRpcRequest req = new A2aJsonRpcRequest("2.0", "req-101", "tasks.create",
                Map.of("prompt", "分析招商中证白酒", "assetCategory", "FUND"));
        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("tasks.create"));
        assertTrue(json.contains("req-101"));

        A2aJsonRpcRequest readReq = mapper.readValue(json, A2aJsonRpcRequest.class);
        assertEquals("tasks.create", readReq.method());
        assertEquals("分析招商中证白酒", readReq.params().get("prompt"));

        A2aTaskEvent event = new A2aTaskEvent("text_delta", "task-1", "正在获取指标...", Instant.now());
        String eventJson = mapper.writeValueAsString(event);
        assertTrue(eventJson.contains("text_delta"));
        assertTrue(eventJson.contains("task-1"));
    }
}

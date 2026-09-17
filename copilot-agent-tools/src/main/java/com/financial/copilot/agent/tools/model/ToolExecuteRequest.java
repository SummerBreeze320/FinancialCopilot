package com.financial.copilot.agent.tools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 统一 Tool 执行请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecuteRequest {

    /** 工具唯一标识或名称 */
    private String toolId;

    /** 调用入参键值对 */
    private Map<String, Object> arguments;

    /** 当前会话与链路上下文 */
    private String sessionId;

    /** 当前任务 Trace ID */
    private String traceId;

    /** 来源执行模式 (LOCAL_PROJECT 或 EXTERNAL_CONFIGURED) */
    @Builder.Default
    private ToolSourceMode sourceMode = ToolSourceMode.EXTERNAL_CONFIGURED;
}

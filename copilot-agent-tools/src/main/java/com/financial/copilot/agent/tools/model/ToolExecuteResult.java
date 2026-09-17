package com.financial.copilot.agent.tools.model;

import com.financial.copilot.agent.tools.workspace.ToolWorkspacePayload;
import com.financial.copilot.agent.tools.workspace.ToolWorkspaceReference;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 统一 Tool 双轨执行结果。
 * 严格践行方案 A：单一执行，双轨交付。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecuteResult {

    /** 执行是否成功 */
    private boolean success;

    /**
     * 【轻轨】供 Agent LLM 观察窗口 (Observation) 消费的精简量化事实。
     * 由 ComponentDataDistiller 纯内存规则提炼，压缩至 100~300 Tokens，杜绝上下文膨胀。
     */
    private String textForLlm;

    /**
     * 【重轨】供前端工作台 (Workspace Canvas) 渲染的交互组件载荷。
     * 包含 100% 原始分辨率的 ECharts 配置与表格全量行列数据。
     */
    private UITreeComponent visualComponent;

    /** 批量伴生组件列表 (如果单个 Tool 执行触发多个关联卡片) */
    private List<UITreeComponent> visualComponents;

    /** 底层返回的原生强类型数据 */
    private Map<String, Object> rawData;

    /** 错误信息 (若失败) */
    private String errorMessage;

    /** 工作台聚合载荷 */
    private ToolWorkspacePayload workspacePayload;

    /** 供 LLM/Agent 引用的组件索引 */
    private List<ToolWorkspaceReference> references;

    public static ToolExecuteResult ofSuccess(String textForLlm, UITreeComponent component, Map<String, Object> rawData) {
        return ToolExecuteResult.builder()
                .success(true)
                .textForLlm(textForLlm)
                .visualComponent(component)
                .rawData(rawData)
                .build();
    }

    public static ToolExecuteResult ofFailure(String errorMessage) {
        return ToolExecuteResult.builder()
                .success(false)
                .textForLlm("【执行失败】: " + errorMessage)
                .errorMessage(errorMessage)
                .build();
    }
}

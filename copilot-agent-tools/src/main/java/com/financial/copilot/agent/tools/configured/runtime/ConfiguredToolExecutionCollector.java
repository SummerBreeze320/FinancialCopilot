package com.financial.copilot.agent.tools.configured.runtime;

import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 线程隔离的配置化工具执行结果收集器。
 * 用于解耦 AgentScope @Tool 接口要求的纯文本返回与上层 Core/Workspace 的重轨结构化交付。
 */
public final class ConfiguredToolExecutionCollector {

    private static final ThreadLocal<List<ToolExecuteResult>> CURRENT = ThreadLocal.withInitial(ArrayList::new);

    private ConfiguredToolExecutionCollector() {}

    public static void record(ToolExecuteResult result) {
        if (result != null) {
            CURRENT.get().add(result);
        }
    }

    public static List<ToolExecuteResult> snapshot() {
        return List.copyOf(CURRENT.get());
    }

    public static List<ToolExecuteResult> drain() {
        List<ToolExecuteResult> copy = snapshot();
        CURRENT.get().clear();
        return copy;
    }

    public static void clear() {
        CURRENT.get().clear();
    }
}

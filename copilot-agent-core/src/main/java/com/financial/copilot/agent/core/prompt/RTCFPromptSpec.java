package com.financial.copilot.agent.core.prompt;

import com.financial.copilot.agent.core.llm.dto.LlmRequest;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * <h1>RTCF 规范化提示词规格说明 (Role, Task, Context, Format)</h1>
 * <p>
 * 遵循业界最佳实践与 Context Engineering 架构规范，杜绝把背景、要求、示例与零散规则无序混杂：
 * <ul>
 *     <li><b>Role (角色与心智)</b>: 明确智能体专业人设与纪律边界，映射为 API 级 System Prompt，前缀稳定以支持 Context Caching；</li>
 *     <li><b>Task (目标任务)</b>: 当前执行轮次的明确目标指令；</li>
 *     <li><b>Context (事实上下文)</b>: 分槽装配清洗后的观察值、用户画像、历史记忆与命中 Skill；</li>
 *     <li><b>Format (输出契约)</b>: 严格约定返回格式 (JSON Schema / Markdown 结构)。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
@Builder
public class RTCFPromptSpec {

    /**
     * 角色设定 (System Prompt 核心)
     */
    private final String role;

    /**
     * 核心纪律与边界准则 (如严格防幻觉、Tool-as-Truth 等)
     */
    @Builder.Default
    private final List<String> coreDisciplines = new ArrayList<>();

    /**
     * 当前轮次执行的明确任务 (Task)
     */
    private final String task;

    /**
     * 结构化上下文事实槽 (Context)
     */
    @Builder.Default
    private final List<ContextSlot> contextSlots = new ArrayList<>();

    /**
     * 格式约束要求 (Format / Output Constraints)
     */
    private final String format;

    @Getter
    @Builder
    public static class ContextSlot {
        private final String slotName;
        private final String content;
    }

    public static RTCFPromptSpecBuilder builder() {
        return new RTCFPromptSpecBuilder();
    }

    /**
     * 渲染模型系统提示词 (System Prompt)
     * 保持静态前缀稳定，最大化复用 LLM 服务商的 Context Caching 缓存
     */
    public String renderSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        if (role != null && !role.isBlank()) {
            sb.append("【角色定位】\n").append(role.trim()).append("\n\n");
        }
        if (coreDisciplines != null && !coreDisciplines.isEmpty()) {
            sb.append("【核心纪律与原则】\n");
            for (int i = 0; i < coreDisciplines.size(); i++) {
                sb.append(i + 1).append(". ").append(coreDisciplines.get(i).trim()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 渲染模型用户提示词 (User Prompt)
     * 清晰组织 Task、Context 各事实槽、以及最后的 Format 输出要求
     */
    public String renderUserPrompt() {
        StringBuilder sb = new StringBuilder();

        // 1. Task 目标
        if (task != null && !task.isBlank()) {
            sb.append("【当前任务 (Task)】\n").append(task.trim()).append("\n\n");
        }

        // 2. Context 事实槽
        if (contextSlots != null && !contextSlots.isEmpty()) {
            sb.append("【事实与上下文 (Context)】\n");
            for (ContextSlot slot : contextSlots) {
                if (slot != null && slot.getContent() != null && !slot.getContent().isBlank()) {
                    sb.append("### [").append(slot.getSlotName()).append("]\n")
                      .append(slot.getContent().trim()).append("\n\n");
                }
            }
        }

        // 3. Format 格式约束
        if (format != null && !format.isBlank()) {
            sb.append("【输出格式与约束 (Format)】\n").append(format.trim());
        }

        return sb.toString().trim();
    }

    /**
     * 转换为统一的 LlmRequest
     */
    public LlmRequest toLlmRequest(LlmSettingsDTO settings) {
        return LlmRequest.of(renderSystemPrompt(), renderUserPrompt(), settings);
    }

    public LlmRequest toLlmRequest() {
        return toLlmRequest(null);
    }
}

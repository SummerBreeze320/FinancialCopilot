package com.financial.copilot.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * <h1>多智能体投研流式交互事件模型 (ResearchStreamEvent)</h1>
 * <p>
 * 面向原生 Supervisor-Subagent 多智能体体系的流式 SSE 协议模型。
 * 表达总控调度思考、专业子智能体派发、真实工具调用与流式研报合成等全生命周期事件。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResearchStreamEvent {

    /**
     * 事件类型 (小写下划线标准规范):
     * <ul>
     *     <li>{@code run_queued}: 排队等待准入许可</li>
     *     <li>{@code run_started}: 投研运行正式启动</li>
     *     <li>{@code supervisor_thinking}: 总控主管意图识别与任务规划思考</li>
     *     <li>{@code subagent_started}: 专业子智能体启动执行</li>
     *     <li>{@code tool_called}: 智能体调用具体金融量化工具</li>
     *     <li>{@code tool_completed}: 具体金融工具执行完毕</li>
     *     <li>{@code subagent_completed}: 专业子智能体执行完成并沉淀事实证据</li>
     *     <li>{@code content_chunk}: 研报文本流式增量块</li>
     *     <li>{@code run_completed}: 全链路投研完成并交付成果</li>
     *     <li>{@code run_failed}: 运行失败 (含对抗性拦截或系统异常)</li>
     *     <li>{@code run_cancelled}: 运行被用户或系统取消</li>
     * </ul>
     */
    private String type;

    /** 运行唯一标识 */
    private String runId;

    /** 会话唯一标识 */
    private String conversationId;

    /** 智能体角色标识 (如 research_supervisor, fund_research_agent 等) */
    private String agentRole;

    /** 子智能体单次运行实例标识 */
    private String agentRunId;

    /** 调用的金融工具名称 (如 getFundMetrics, screenFunds 等) */
    private String toolName;

    /** 流式输出的增量文本内容 */
    private String chunk;

    /** 状态标识 (QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED 等) */
    private String status;

    /** 阶段性摘要、原因或审查意见 */
    private String summary;

    /** 运行时长 (毫秒) */
    private Long durationMs;

    /** 事件在当前 Run 下的单调递增序号 (从 1 开始) */
    private Long sequenceNo;

    /** 沉淀或引用的事实证据数量 */
    private Integer evidenceCount;

    /** 附加扩展结构化载荷 (如排队位次、拦截详情等) */
    private Map<String, Object> payload;

    // -------------------------------------------------------------
    // 原生多智能体事件工厂方法
    // -------------------------------------------------------------

    public static ResearchStreamEvent runQueued(String runId, String conversationId, String reason, Integer queuePosition) {
        return ResearchStreamEvent.builder()
                .type("run_queued")
                .runId(runId)
                .conversationId(conversationId)
                .status("QUEUED")
                .summary(reason)
                .payload(queuePosition != null ? Map.of("queuePosition", queuePosition) : null)
                .build();
    }

    public static ResearchStreamEvent runStarted(String runId, String conversationId) {
        return ResearchStreamEvent.builder()
                .type("run_started")
                .runId(runId)
                .conversationId(conversationId)
                .status("RUNNING")
                .build();
    }

    public static ResearchStreamEvent runAdmitted(String runId, String conversationId) {
        return ResearchStreamEvent.builder()
                .type("run_admitted")
                .runId(runId)
                .conversationId(conversationId)
                .status("ADMITTED")
                .build();
    }

    public static ResearchStreamEvent supervisorThinking(String runId, String summary) {
        return ResearchStreamEvent.builder()
                .type("supervisor_thinking")
                .runId(runId)
                .agentRole("research_supervisor")
                .status("RUNNING")
                .summary(summary != null ? summary : "投研总控正在理解意图与规划任务...")
                .build();
    }

    public static ResearchStreamEvent subagentStarted(String runId, String agentRunId, String agentRole, String summary) {
        return ResearchStreamEvent.builder()
                .type("subagent_started")
                .runId(runId)
                .agentRunId(agentRunId)
                .agentRole(agentRole)
                .status("RUNNING")
                .summary(summary)
                .build();
    }

    public static ResearchStreamEvent toolCalled(String runId, String agentRunId, String agentRole, String toolName, Map<String, Object> inputPayload) {
        return ResearchStreamEvent.builder()
                .type("tool_called")
                .runId(runId)
                .agentRunId(agentRunId)
                .agentRole(agentRole)
                .toolName(toolName)
                .status("RUNNING")
                .payload(inputPayload)
                .build();
    }

    public static ResearchStreamEvent toolCompleted(String runId, String agentRunId, String agentRole, String toolName, String summary) {
        return ResearchStreamEvent.builder()
                .type("tool_completed")
                .runId(runId)
                .agentRunId(agentRunId)
                .agentRole(agentRole)
                .toolName(toolName)
                .status("SUCCEEDED")
                .summary(summary)
                .build();
    }

    public static ResearchStreamEvent subagentCompleted(String runId, String agentRunId, String agentRole, String summary, Integer evidenceCount) {
        return ResearchStreamEvent.builder()
                .type("subagent_completed")
                .runId(runId)
                .agentRunId(agentRunId)
                .agentRole(agentRole)
                .status("SUCCEEDED")
                .summary(summary)
                .evidenceCount(evidenceCount)
                .build();
    }

    public static ResearchStreamEvent contentChunk(String runId, String chunk) {
        return ResearchStreamEvent.builder()
                .type("content_chunk")
                .runId(runId)
                .chunk(chunk)
                .build();
    }

    public static ResearchStreamEvent runCompleted(String runId, Long durationMs) {
        return runCompleted(runId, durationMs, null);
    }

    public static ResearchStreamEvent runCompleted(String runId, String status, Long durationMs) {
        return ResearchStreamEvent.builder()
                .type("run_completed")
                .runId(runId)
                .status(status != null ? status : "SUCCEEDED")
                .durationMs(durationMs)
                .build();
    }

    public static ResearchStreamEvent runCompleted(String runId, Long durationMs, Integer evidenceCount) {
        return ResearchStreamEvent.builder()
                .type("run_completed")
                .runId(runId)
                .status("SUCCEEDED")
                .durationMs(durationMs)
                .evidenceCount(evidenceCount)
                .build();
    }

    public static ResearchStreamEvent runFailed(String runId, String error) {
        return ResearchStreamEvent.builder()
                .type("run_failed")
                .runId(runId)
                .status("FAILED")
                .summary(error)
                .build();
    }

    public static ResearchStreamEvent runCancelled(String runId, String reason) {
        return ResearchStreamEvent.builder()
                .type("run_cancelled")
                .runId(runId)
                .status("CANCELLED")
                .summary(reason)
                .build();
    }
}

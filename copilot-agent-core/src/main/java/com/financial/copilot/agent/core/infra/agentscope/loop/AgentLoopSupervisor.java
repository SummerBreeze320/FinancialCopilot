package com.financial.copilot.agent.core.infra.agentscope.loop;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>Agent 运行时终止状态机与死循环守护器 (AgentLoopSupervisor)</h1>
 * <p>
 * 严格对齐四大终止条件与防死循环策略规范：
 * <ul>
 *     <li><b>a) 任务完成判定 (TASK_COMPLETED)</b>：模型判断目标达成，正常输出最终答案，无下一步工具动作；</li>
 *     <li><b>b) 无可用下一步动作 (NO_AVAILABLE_ACTION)</b>：模型请求的工具全部处于熔断/权限禁用状态，信息不足且无路可走；</li>
 *     <li><b>c) 达到最大轮数 (MAX_ITERATIONS_REACHED)</b>：硬上限兜底，截断死循环，输出阶段性已观测结果；</li>
 *     <li><b>d) 不可恢复错误 (UNRECOVERABLE_ERROR)</b>：工具持续失败熔断 (TOOL_CIRCUIT_BROKEN)、权限拒绝 (PERMISSION_DENIED)；</li>
 *     <li><b>防死循环防护</b>：连续相同参数去重 (CONSECUTIVE_DUPLICATE_CALL)、往复振荡死锁 (PING_PONG_OSCILLATION)。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
public class AgentLoopSupervisor {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopSupervisor.class);

    private final int maxIterations;
    private final int maxConsecutiveDuplicates;
    private final int maxToolFailures;

    private final List<ToolCallFingerprint> callHistory = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> toolFailureCounts = new ConcurrentHashMap<>();
    private final Set<String> trippedTools = ConcurrentHashMap.newKeySet();
    private final Set<String> permissionDeniedTools = ConcurrentHashMap.newKeySet();
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    @Getter
    private volatile boolean terminated = false;

    @Getter
    private volatile AgentTerminationStatus terminationStatus = null;

    @Getter
    private volatile String fallbackSummary = null;

    public AgentLoopSupervisor() {
        this(10, 2, 2);
    }

    public AgentLoopSupervisor(int maxIterations, int maxConsecutiveDuplicates, int maxToolFailures) {
        this.maxIterations = maxIterations > 0 ? maxIterations : 10;
        this.maxConsecutiveDuplicates = maxConsecutiveDuplicates > 0 ? maxConsecutiveDuplicates : 2;
        this.maxToolFailures = maxToolFailures > 0 ? maxToolFailures : 2;
    }

    /**
     * 兼容返回终止原因的字符串名称
     *
     * @return 终止原因字符串（未终止且未完成时为 null）
     */
    public String getTerminationReason() {
        return terminationStatus != null ? terminationStatus.name() : null;
    }

    /**
     * 记录最新收到的工具执行观测结果，维护连续失败状态、熔断标记与不可恢复权限拒绝
     *
     * @param results 最新收到的工具执行结果块列表
     */
    public void recordObservations(List<ToolResultBlock> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (ToolResultBlock result : results) {
            String toolName = result.getName();
            if (toolName == null || toolName.isBlank()) {
                continue;
            }

            // 1. 检查不可恢复的权限拒绝错误
            if (isOutputPermissionDenied(result)) {
                permissionDeniedTools.add(toolName);
                log.error("[AgentLoopSupervisor] 工具 [{}] 产生不可恢复权限拒绝错误，已被权限拉黑！", toolName);
                continue;
            }

            // 2. 检查普通异常与连续失败熔断
            if (result.getState() == ToolResultState.ERROR || isOutputError(result)) {
                AtomicInteger tracker = toolFailureCounts.computeIfAbsent(toolName, k -> new AtomicInteger(0));
                int count = tracker.incrementAndGet();
                log.warn("[AgentLoopSupervisor] 工具 [{}] 连续执行失败，当前失败计数: {} / {}", toolName, count, maxToolFailures);
                if (count >= maxToolFailures) {
                    trippedTools.add(toolName);
                    log.error("[AgentLoopSupervisor] 工具 [{}] 累计连续失败达到阈值 ({})，已被熔断保护！", toolName, maxToolFailures);
                }
            } else if (result.getState() == ToolResultState.SUCCESS) {
                AtomicInteger tracker = toolFailureCounts.get(toolName);
                if (tracker != null) {
                    tracker.set(0);
                }
            }
        }
    }

    private boolean isOutputPermissionDenied(ToolResultBlock result) {
        if (result.getOutput() == null) return false;
        for (Object block : result.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text != null && (text.contains("权限不足") || text.contains("PermissionDenied")
                        || text.contains("AccessDenied") || text.contains("403 Forbidden")
                        || text.contains("无权调用") || text.contains("越权访问") || text.contains("权限拒绝"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isOutputError(ToolResultBlock result) {
        if (result.getOutput() == null) return false;
        for (Object block : result.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text != null && (text.contains("Exception") || text.contains("调用超限熔断") || text.contains("执行失败:"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断指定工具当前是否已被熔断
     *
     * @param toolName 工具名称
     * @return true 若已处于熔断硬阻断状态
     */
    public boolean isToolTripped(String toolName) {
        return toolName != null && trippedTools.contains(toolName);
    }

    /**
     * 判断指定工具是否已被权限拒绝
     *
     * @param toolName 工具名称
     * @return true 若已属于权限拒绝不可恢复状态
     */
    public boolean isPermissionDenied(String toolName) {
        return toolName != null && permissionDeniedTools.contains(toolName);
    }

    /**
     * 响应外部取消信号，转换为 CANCELLED 终止状态
     */
    public void recordCancellation() {
        this.terminated = true;
        this.terminationStatus = AgentTerminationStatus.CANCELLED;
        this.fallbackSummary = "[系统终止] 任务已被外部取消或中断。";
    }

    /**
     * 校验大模型最新输出的 ChatResponse，驱动终止状态机并执行死循环守卫
     *
     * @param response 大模型当轮生成的 ChatResponse
     * @param currentObservations 当前节点已收集到的完整工具观测字典
     * @return 经过守卫校验后的 ChatResponse
     */
    public ChatResponse inspectAndGuard(ChatResponse response, Map<String, List<ToolResultBlock>> currentObservations) {
        if (response == null) {
            return null;
        }

        // c) 达到最大轮数 (硬上限兜底)
        int turn = turnCounter.incrementAndGet();
        if (turn >= maxIterations) {
            log.warn("[AgentLoopSupervisor] 达到最大允许迭代轮数 ({} 次)，触发最大轮数硬上限兜底终止", maxIterations);
            return terminate(response, AgentTerminationStatus.MAX_ITERATIONS_REACHED,
                    "已达到单节点最大允许迭代轮数 (" + maxIterations + "轮)，系统触发硬上限安全收敛。", currentObservations);
        }

        List<ToolUseBlock> toolUses = extractToolUses(response);

        // a) 任务完成判定：无下一步工具调用，模型给出最终文本答复
        if (toolUses.isEmpty()) {
            if (!this.terminated) {
                this.terminationStatus = AgentTerminationStatus.TASK_COMPLETED;
            }
            return response;
        }

        for (ToolUseBlock toolUse : toolUses) {
            String toolName = toolUse.getName();

            // d2) 不可恢复错误：权限拒绝硬拦截
            if (isPermissionDenied(toolName)) {
                log.warn("[AgentLoopSupervisor] 拦截到 Agent 试图再次调用权限拒绝工具: [{}]", toolName);
                return terminate(response, AgentTerminationStatus.PERMISSION_DENIED,
                        "工具 [" + toolName + "] 权限校验拒绝。此错误无法通过反复重试自愈，系统已安全终止。", currentObservations);
            }

            // d1) 不可恢复错误：熔断工具硬阻断
            if (isToolTripped(toolName)) {
                log.warn("[AgentLoopSupervisor] 拦截到 Agent 试图再次调用已熔断工具: [{}]", toolName);
                return terminate(response, AgentTerminationStatus.TOOL_CIRCUIT_BROKEN,
                        "工具 [" + toolName + "] 因连续失败已进入熔断保护，系统拒绝重复执行该故障工具。", currentObservations);
            }

            ToolCallFingerprint currentFingerprint = ToolCallFingerprint.of(toolName, toolUse.getInput());

            // 防死循环保护 1: 连续完全相同入参重复调用检测
            if (!callHistory.isEmpty()) {
                ToolCallFingerprint lastFingerprint = callHistory.get(callHistory.size() - 1);
                if (lastFingerprint.equals(currentFingerprint)) {
                    log.warn("[AgentLoopSupervisor] 检测到连续以完全相同入参调用工具: [{}]，指纹: {}", toolName, currentFingerprint);
                    return terminate(response, AgentTerminationStatus.CONSECUTIVE_DUPLICATE_CALL,
                            "检测到以完全相同的入参连续重复调用工具 [" + toolName + "]，系统已阻断死循环路径。", currentObservations);
                }
            }

            // 防死循环保护 2: 周期性振荡往复调用检测 (如 A -> B -> A -> B)
            if (detectOscillation(currentFingerprint)) {
                log.warn("[AgentLoopSupervisor] 检测到工具调用振荡环路死锁，最新触发工具: [{}]", toolName);
                return terminate(response, AgentTerminationStatus.PING_PONG_OSCILLATION,
                        "检测到工具间存在交替往复振荡调用，判定为推理死锁，触发安全终止保护。", currentObservations);
            }

            // 校验通过，记录至调用历史
            callHistory.add(currentFingerprint);
        }

        return response;
    }

    private boolean detectOscillation(ToolCallFingerprint candidate) {
        List<ToolCallFingerprint> sequence = new ArrayList<>(callHistory);
        sequence.add(candidate);
        int total = sequence.size();

        // 检查周期 p = 2 (A->B->A->B) 与 p = 3 (A->B->C->A->B->C)
        for (int p = 2; p <= 3; p++) {
            if (total >= 2 * p) {
                boolean match = true;
                for (int i = 0; i < p; i++) {
                    ToolCallFingerprint recent = sequence.get(total - 1 - i);
                    ToolCallFingerprint previous = sequence.get(total - 1 - p - i);
                    if (!recent.equals(previous)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ToolUseBlock> extractToolUses(ChatResponse response) {
        if (response.getContent() == null) {
            return List.of();
        }
        List<ToolUseBlock> toolUses = new ArrayList<>();
        for (Object block : response.getContent()) {
            if (block instanceof ToolUseBlock toolUse) {
                toolUses.add(toolUse);
            }
        }
        return toolUses;
    }

    private ChatResponse terminate(ChatResponse original, AgentTerminationStatus status, String details,
                                   Map<String, List<ToolResultBlock>> observations) {
        this.terminated = true;
        this.terminationStatus = status;

        StringBuilder summary = new StringBuilder();
        summary.append("[Agent终止保护机制触发 (").append(status.getDescription()).append(")] ").append(details).append("\n\n");
        summary.append("【阶段性已获取数据与观测总结】：\n");
        if (observations == null || observations.isEmpty()) {
            summary.append("（尚未成功获取到有效的量化工具观测数据）");
        } else {
            observations.forEach((toolName, results) -> {
                summary.append("- 工具 [").append(toolName).append("]：成功执行 ").append(results.size()).append(" 次\n");
            });
        }
        this.fallbackSummary = summary.toString();

        return ChatResponse.builder()
                .id(original.getId() != null ? original.getId() : "intercepted-stop")
                .finishReason("stop")
                .content(List.of(TextBlock.builder().text(this.fallbackSummary).build()))
                .usage(original.getUsage())
                .build();
    }
}

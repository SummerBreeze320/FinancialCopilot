package com.financial.copilot.agent.core.infra.security;

import java.util.Set;

/**
 * <h1>用户工具权限白名单解析服务</h1>
 * <p>
 * 决定指定用户拥有调用哪些工具 (Tool) 的权限。
 * 若返回包含 "*"，代表全量放行；若返回特定 Set，则严格白名单限制。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface UserToolPermissionService {

    /**
     * 获取指定用户的可用工具白名单
     *
     * @param userId 用户 ID (可为 null，代表匿名或系统执行身份)
     * @return 允许调用的工具名称集合，若全量放行可返回包含 "*" 的集合
     */
    Set<String> getAllowedTools(Long userId);

    /**
     * 判断指定用户是否有权调用特定工具
     *
     * @param userId   用户 ID
     * @param toolName 工具名称
     * @return true 若允许
     */
    default boolean isToolAllowed(Long userId, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        Set<String> allowed = getAllowedTools(userId);
        if (allowed == null || allowed.isEmpty() || allowed.contains("*")) {
            return true;
        }
        return allowed.contains(toolName);
    }
}

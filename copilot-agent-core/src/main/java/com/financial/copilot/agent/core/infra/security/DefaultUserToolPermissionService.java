package com.financial.copilot.agent.core.infra.security;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>默认用户工具权限白名单服务</h1>
 * <p>
 * 支持运行时动态为指定用户配置个性化白名单（如按 VIP 权限或普通用户权限隔离），
 * 默认对未单独配置的用户开放全量基准工具。
 * </p>
 *
 * @author FinancialCopilot
 */
@Service
public class DefaultUserToolPermissionService implements UserToolPermissionService {

    private final Map<Long, Set<String>> userWhitelists = new ConcurrentHashMap<>();

    @Override
    public Set<String> getAllowedTools(Long userId) {
        if (userId == null) {
            return Collections.singleton("*");
        }
        return userWhitelists.getOrDefault(userId, Collections.singleton("*"));
    }

    /**
     * 为指定用户动态设置白名单
     *
     * @param userId 用户 ID
     * @param tools  允许调用的工具集合 (传入 null 或包含 "*" 则恢复为全量放行)
     */
    public void setUserWhitelist(Long userId, Set<String> tools) {
        if (userId == null) {
            return;
        }
        if (tools == null || tools.isEmpty() || tools.contains("*")) {
            userWhitelists.remove(userId);
        } else {
            userWhitelists.put(userId, Set.copyOf(tools));
        }
    }

    /**
     * 清理指定用户的白名单限制，恢复全量放行
     *
     * @param userId 用户 ID
     */
    public void clearUserWhitelist(Long userId) {
        if (userId != null) {
            userWhitelists.remove(userId);
        }
    }
}

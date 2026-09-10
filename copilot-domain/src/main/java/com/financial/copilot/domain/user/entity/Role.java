package com.financial.copilot.domain.user.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <h1>系统角色领域实体 (Role Domain Entity)</h1>
 * <p>
 * 职责：定义 RBAC 角色体系（ROLE_ADMIN, ROLE_ANALYST, ROLE_USER 等）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role implements Serializable {

    /** 角色主键 ID */
    private Long id;

    /** 唯一角色标识代码 (如 ROLE_ADMIN, ROLE_ANALYST, ROLE_USER) */
    private String roleCode;

    /** 角色直观显示名称 (如 "平台研发管理员", "专业机构分析师") */
    private String roleName;

    /** 角色权责描述 */
    private String description;

    /** 是否为系统内置不可删除角色 */
    @Builder.Default
    private boolean isSystem = true;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

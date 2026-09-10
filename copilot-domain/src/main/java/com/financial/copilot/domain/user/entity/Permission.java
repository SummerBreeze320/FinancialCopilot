package com.financial.copilot.domain.user.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <h1>系统权限与资源访问控制点实体 (Permission Domain Entity)</h1>
 * <p>
 * 职责：定义方法级或 API 路由级的最小权限单元（如 research:chat, research:thinking, admin:llm:config 等）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Permission implements Serializable {

    /** 权限主键 ID */
    private Long id;

    /** 唯一权限代码 (如 research:chat, admin:llm:config) */
    private String permCode;

    /** 权限名称 */
    private String permName;

    /** 资源类型 (API, BUTTON, MENU 等) */
    @Builder.Default
    private String resourceType = "API";

    /** 关联的 API 路径 (如 /api/v1/admin/**) */
    private String path;

    /** 关联的 HTTP 动作 (GET, POST, PUT, DELETE 或 * 通配) */
    private String method;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

package com.financial.copilot.data.user.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.platform.user.entity.Permission;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h1>系统权限点持久化对象 (Permission PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_permission")
public class PermissionPO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 权限唯一标识编码（例如 "fund:read", "fund:execute"）
     */
    private String permCode;

    /**
     * 权限显示名称（例如 "查看基金画像", "执行量化筛选"）
     */
    private String permName;

    /**
     * 资源类型（API 接口 / MENU 菜单 / BUTTON 按钮等）
     */
    private String resourceType;

    /**
     * 资源路径/Ant表达式（例如 "/api/v1/funds/**"）
     */
    private String path;

    /**
     * HTTP 方法（GET, POST, PUT, DELETE, *）
     */
    private String method;

    /**
     * 权限创建时间戳
     */
    private LocalDateTime createdAt;

    public Permission toDomain() {
        return Permission.builder()
                .id(id)
                .permCode(permCode)
                .permName(permName)
                .resourceType(resourceType != null ? resourceType : "API")
                .path(path)
                .method(method)
                .createdAt(createdAt)
                .build();
    }

    public static PermissionPO fromDomain(Permission domain) {
        if (domain == null) return null;
        return PermissionPO.builder()
                .id(domain.getId())
                .permCode(domain.getPermCode())
                .permName(domain.getPermName())
                .resourceType(domain.getResourceType())
                .path(domain.getPath())
                .method(domain.getMethod())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}

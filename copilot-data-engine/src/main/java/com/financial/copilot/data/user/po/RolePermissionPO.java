package com.financial.copilot.data.user.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h1>角色-权限关联持久化对象 (Role-Permission Mapping PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_role_permission")
public class RolePermissionPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long roleId;

    private Long permissionId;
}

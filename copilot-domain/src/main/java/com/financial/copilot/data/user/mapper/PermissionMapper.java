package com.financial.copilot.data.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.user.po.PermissionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <h1>系统权限点数据映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface PermissionMapper extends BaseMapper<PermissionPO> {

    /**
     * 根据角色 ID 联表查询关联权限点列表
     *
     * @param roleId 角色 ID
     * @return 权限 PO 列表
     */
    @Select("""
        SELECT p.* FROM sys_permission p
        INNER JOIN sys_role_permission rp ON p.id = rp.permission_id
        WHERE rp.role_id = #{roleId}
    """)
    List<PermissionPO> selectPermissionsByRoleId(Long roleId);

    /**
     * 根据用户 ID 联表查询聚合权限点列表
     *
     * @param userId 用户 ID
     * @return 权限 PO 列表
     */
    @Select("""
        SELECT DISTINCT p.* FROM sys_permission p
        INNER JOIN sys_role_permission rp ON p.id = rp.permission_id
        INNER JOIN sys_user_role ur ON rp.role_id = ur.role_id
        WHERE ur.user_id = #{userId}
    """)
    List<PermissionPO> selectPermissionsByUserId(Long userId);
}

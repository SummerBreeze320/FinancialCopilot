package com.financial.copilot.data.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.user.po.RolePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <h1>系统角色数据映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface RoleMapper extends BaseMapper<RolePO> {

    /**
     * 根据用户 ID 联表查询拥有的角色列表
     *
     * @param userId 用户 ID
     * @return 角色 PO 列表
     */
    @Select("""
        SELECT r.* FROM sys_role r
        INNER JOIN sys_user_role ur ON r.id = ur.role_id
        WHERE ur.user_id = #{userId}
    """)
    List<RolePO> selectRolesByUserId(Long userId);
}

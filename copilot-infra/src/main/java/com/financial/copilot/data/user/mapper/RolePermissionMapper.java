package com.financial.copilot.data.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.user.po.RolePermissionPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>角色权限关系映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermissionPO> {
}

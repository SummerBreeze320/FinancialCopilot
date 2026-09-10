package com.financial.copilot.data.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.user.po.UserRolePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>用户角色关系映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRolePO> {
}

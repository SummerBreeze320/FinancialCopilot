package com.financial.copilot.data.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.user.po.UserProfilePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>用户基础资料映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfilePO> {
}

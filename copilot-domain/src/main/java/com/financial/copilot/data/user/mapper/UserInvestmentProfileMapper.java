package com.financial.copilot.data.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.user.po.UserInvestmentProfilePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>用户投资画像与风险偏好映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface UserInvestmentProfileMapper extends BaseMapper<UserInvestmentProfilePO> {
}

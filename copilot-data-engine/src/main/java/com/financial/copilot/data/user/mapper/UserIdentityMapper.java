package com.financial.copilot.data.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.user.po.UserIdentityPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>金融实名认证映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface UserIdentityMapper extends BaseMapper<UserIdentityPO> {
}

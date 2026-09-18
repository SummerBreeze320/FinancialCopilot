package com.financial.copilot.data.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.user.po.UserPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>系统用户数据映射器 (MyBatis-Plus Mapper)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface UserMapper extends BaseMapper<UserPO> {
}

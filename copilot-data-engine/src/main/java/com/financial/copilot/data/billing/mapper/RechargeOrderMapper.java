package com.financial.copilot.data.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.billing.po.RechargeOrderPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h1>充值交易订单持久层 Mapper (MyBatis-Plus)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrderPO> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM sys_recharge_order WHERE order_no = #{orderNo} FOR UPDATE")
    RechargeOrderPO selectForUpdate(@org.apache.ibatis.annotations.Param("orderNo") String orderNo);
}

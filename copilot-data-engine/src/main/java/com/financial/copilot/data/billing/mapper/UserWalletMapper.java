package com.financial.copilot.data.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.billing.po.UserWalletPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <h1>用户算力钱包持久层 Mapper (MyBatis-Plus)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface UserWalletMapper extends BaseMapper<UserWalletPO> {
    @org.apache.ibatis.annotations.Insert("INSERT INTO sys_user_wallet " +
            "(user_id, tenant_id, balance_points, frozen_points, total_recharged_points, total_consumed_points, wallet_status, version, updated_at) " +
            "VALUES (#{userId}, #{tenantId}, 0, 0, 0, 0, 'NORMAL', 0, NOW()) ON CONFLICT (user_id) DO NOTHING")
    int createIfAbsent(@Param("userId") Long userId, @Param("tenantId") String tenantId);

    @Update("UPDATE sys_user_wallet SET balance_points = balance_points - #{points}, " +
            "total_consumed_points = total_consumed_points + #{points}, version = version + 1, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND wallet_status = 'NORMAL' AND balance_points - frozen_points >= #{points}")
    int deductAvailable(@Param("userId") Long userId, @Param("points") long points);

    /**
     * 基于版本号乐观锁与余额校验的原子扣减 (防止超扣与并发脏写)
     *
     * @param userId  用户 ID
     * @param points  扣减点数
     * @param version 当前版本号
     * @return 影响行数 (1 成功，0 失败)
     */
    @Update("UPDATE sys_user_wallet " +
            "SET balance_points = balance_points - #{points}, " +
            "    total_consumed_points = total_consumed_points + #{points}, " +
            "    version = version + 1, " +
            "    updated_at = NOW() " +
            "WHERE user_id = #{userId} AND balance_points >= #{points} AND version = #{version}")
    int deductWithLock(@Param("userId") Long userId,
                       @Param("points") Long points,
                       @Param("version") Long version);

    /**
     * 充值到账：原子增加可用算力点数与累计充值总额
     *
     * @param userId 用户 ID
     * @param points 增加点数
     * @return 影响行数
     */
    @Update("UPDATE sys_user_wallet " +
            "SET balance_points = balance_points + #{points}, " +
            "    total_recharged_points = total_recharged_points + #{points}, " +
            "    version = version + 1, " +
            "    updated_at = NOW() " +
            "WHERE user_id = #{userId}")
    int addRechargePoints(@Param("userId") Long userId,
                          @Param("points") Long points);
}

package com.financial.copilot.data.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.billing.po.UserWalletPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <h1>用户积分钱包持久层 Mapper (MyBatis-Plus)</h1>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface UserWalletMapper extends BaseMapper<UserWalletPO> {
    /**
     * 若用户钱包尚不存在，则幂等初始化一个余额为 0 的新钱包
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID
     * @return 影响行数
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO sys_user_wallet " +
            "(user_id, tenant_id, balance_points, frozen_points, total_recharged_points, total_consumed_points, wallet_status, version, updated_at) " +
            "VALUES (#{userId}, #{tenantId}, 0, 0, 0, 0, 'NORMAL', 0, NOW()) ON CONFLICT (user_id) DO NOTHING")
    int createIfAbsent(@Param("userId") Long userId, @Param("tenantId") String tenantId);

    /**
     * 扣减用户可用积分（确保可用积分 balance - frozen >= points）
     *
     * @param userId 系统用户 ID
     * @param points 扣减积分数
     * @return 影响行数 (1 扣减成功, 0 余额不足或失败)
     */
    @Update("UPDATE sys_user_wallet SET balance_points = balance_points - #{points}, " +
            "total_consumed_points = total_consumed_points + #{points}, version = version + 1, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND wallet_status = 'NORMAL' AND balance_points - frozen_points >= #{points}")
    int deductAvailable(@Param("userId") Long userId, @Param("points") long points);

    /**
     * 基于版本号乐观锁与余额校验的原子扣减 (防止超扣与并发脏写)
     *
     * @param userId  用户 ID
     * @param points  扣减积分
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
     * 充值到账：原子增加可用积分与累计充值总额
     *
     * @param userId 用户 ID
     * @param points 增加积分
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

    /**
     * 原子预占冻结积分 (确保可用积分 balance - frozen >= estimate)
     *
     * @param userId 系统用户 ID
     * @param estimate 预占冻结积分数
     * @return 影响行数 (1 成功, 0 余额不足或状态异常)
     */
    @Update("UPDATE sys_user_wallet SET frozen_points = frozen_points + #{estimate}, " +
            "version = version + 1, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND wallet_status = 'NORMAL' AND balance_points - frozen_points >= #{estimate}")
    int freezePoints(@Param("userId") Long userId, @Param("estimate") long estimate);

    /**
     * 结算预占：扣除实际消费积分，解冻预占估算积分
     *
     * @param userId 系统用户 ID
     * @param actual 实际消费积分
     * @param estimate 预占估算积分
     * @return 影响行数
     */
    @Update("UPDATE sys_user_wallet SET balance_points = balance_points - #{actual}, " +
            "frozen_points = GREATEST(0, frozen_points - #{estimate}), " +
            "total_consumed_points = total_consumed_points + #{actual}, " +
            "version = version + 1, updated_at = NOW() " +
            "WHERE user_id = #{userId}")
    int settleReservation(@Param("userId") Long userId,
                          @Param("actual") long actual,
                          @Param("estimate") long estimate);

    /**
     * 释放预占冻结积分
     *
     * @param userId 系统用户 ID
     * @param estimate 待释放预占积分数
     * @return 影响行数
     */
    @Update("UPDATE sys_user_wallet SET frozen_points = GREATEST(0, frozen_points - #{estimate}), " +
            "version = version + 1, updated_at = NOW() " +
            "WHERE user_id = #{userId}")
    int releaseFrozen(@Param("userId") Long userId, @Param("estimate") long estimate);
}

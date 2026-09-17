-- ====================================================================
-- FinancialCopilot MySQL 8.x 全量轻量化业务数据库初始化脚本 (lite-mysql 版)
-- 适用范围：用户认证与权限域、计费积分与流水域、对话会话与工具审计域、长短期记忆域
-- ====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 1. 用户域 (User Domain)
-- ----------------------------

DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户唯一自增主键',
    `username` VARCHAR(64) NOT NULL COMMENT '登录账号用户名',
    `password_hash` VARCHAR(128) NOT NULL COMMENT 'BCrypt哈希密码',
    `mobile` VARCHAR(20) DEFAULT NULL COMMENT '绑定手机号',
    `email` VARCHAR(128) DEFAULT NULL COMMENT '绑定电子邮箱',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE, SUSPENDED, DELETED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_mobile` (`mobile`),
    KEY `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户基础表';

DROP TABLE IF EXISTS `sys_user_profile`;
CREATE TABLE `sys_user_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '关联用户ID',
    `nickname` VARCHAR(64) DEFAULT NULL COMMENT '用户昵称',
    `avatar_url` VARCHAR(512) DEFAULT NULL COMMENT '头像地址',
    `real_name` VARCHAR(64) DEFAULT NULL COMMENT '真实姓名',
    `id_card_masked` VARCHAR(32) DEFAULT NULL COMMENT '掩码脱敏身份证号',
    `kyc_status` VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED' COMMENT '实名状态: UNVERIFIED, PENDING, VERIFIED, REJECTED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户档案资料表';

DROP TABLE IF EXISTS `sys_user_investment_profile`;
CREATE TABLE `sys_user_investment_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '关联用户ID',
    `risk_level` VARCHAR(20) NOT NULL DEFAULT 'C3' COMMENT '适格风险评级: C1~C5',
    `investment_horizon` VARCHAR(32) DEFAULT NULL COMMENT '投资期限偏好',
    `investment_style` VARCHAR(32) DEFAULT NULL COMMENT '投资风格偏好: VALUE, GROWTH, BALANCED等',
    `target_annual_return` DECIMAL(10, 4) DEFAULT NULL COMMENT '目标年化收益率',
    `max_drawdown_tolerance` DECIMAL(10, 4) DEFAULT NULL COMMENT '最大承受回撤比例',
    `preferred_sectors` VARCHAR(512) DEFAULT NULL COMMENT '偏好行业板块列表(逗号分隔)',
    `max_single_position_pct` DECIMAL(10, 4) DEFAULT NULL COMMENT '单一标的持仓上限比例',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_investment_profile_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投资者适当性画像表';

DROP TABLE IF EXISTS `sys_user_identity`;
CREATE TABLE `sys_user_identity` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `identity_type` VARCHAR(32) NOT NULL COMMENT '认证类型: WECHAT, GITHUB, ALIPAY',
    `identifier` VARCHAR(128) NOT NULL COMMENT '三方唯一标识',
    `credential` VARCHAR(256) DEFAULT NULL COMMENT '三方授权凭证',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_identity` (`identity_type`, `identifier`),
    KEY `idx_identity_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='三方登录授权身份表';

DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    `role_code` VARCHAR(64) NOT NULL COMMENT '角色唯一编码: ROLE_ADMIN, ROLE_ANALYST, ROLE_USER',
    `role_name` VARCHAR(64) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(256) DEFAULT NULL COMMENT '角色描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    `permission_code` VARCHAR(64) NOT NULL COMMENT '权限唯一编码',
    `permission_name` VARCHAR(64) NOT NULL COMMENT '权限名称',
    `resource_path` VARCHAR(256) DEFAULT NULL COMMENT '后端API资源路径',
    `http_method` VARCHAR(16) DEFAULT NULL COMMENT 'HTTP方法: GET, POST, ALL',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限表';

DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT NOT NULL COMMENT '权限ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- ----------------------------
-- 2. 计费与积分域 (Billing Domain)
-- ----------------------------

DROP TABLE IF EXISTS `sys_user_wallet`;
CREATE TABLE `sys_user_wallet` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `tenant_id` VARCHAR(64) DEFAULT NULL COMMENT '多租户隔离ID',
    `balance_points` BIGINT NOT NULL DEFAULT 0 COMMENT '可用算力积分余额',
    `frozen_points` BIGINT NOT NULL DEFAULT 0 COMMENT '冻结积分(执行中会话预扣)',
    `total_recharged_points` BIGINT NOT NULL DEFAULT 0 COMMENT '历史累计充值积分',
    `total_consumed_points` BIGINT NOT NULL DEFAULT 0 COMMENT '历史累计消耗积分',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户积分钱包表';

DROP TABLE IF EXISTS `sys_model_pricing`;
CREATE TABLE `sys_model_pricing` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `model_name` VARCHAR(128) NOT NULL COMMENT '大模型规格名称: deepseek-chat, qwen-plus等',
    `points_per_thousand_tokens` INT NOT NULL COMMENT '每千Token扣除积分数',
    `active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用生效: 1=启用, 0=下架',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_name` (`model_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='大模型Token计价矩阵表';

DROP TABLE IF EXISTS `sys_recharge_package`;
CREATE TABLE `sys_recharge_package` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `package_name` VARCHAR(128) NOT NULL COMMENT '套餐包名称',
    `price_cny` DECIMAL(10, 2) NOT NULL COMMENT '售卖金额(人民币)',
    `points` BIGINT NOT NULL COMMENT '获得基础积分',
    `bonus_points` BIGINT NOT NULL DEFAULT 0 COMMENT '赠送积分',
    `is_active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否上架: 1=上架, 0=下架',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分充值商品套餐包表';

DROP TABLE IF EXISTS `sys_recharge_order`;
CREATE TABLE `sys_recharge_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `order_no` VARCHAR(64) NOT NULL COMMENT '系统唯一充值流水号',
    `user_id` BIGINT NOT NULL COMMENT '充值用户ID',
    `package_id` BIGINT DEFAULT NULL COMMENT '充值套餐包ID',
    `amount_cny` DECIMAL(10, 2) NOT NULL COMMENT '充值金额(元)',
    `points_to_credit` BIGINT NOT NULL COMMENT '应到账总积分',
    `pay_channel` VARCHAR(32) NOT NULL COMMENT '支付渠道: ALIPAY, WECHAT, MANUAL',
    `third_party_trade_no` VARCHAR(128) DEFAULT NULL COMMENT '外部第三方支付交易订单号',
    `order_status` VARCHAR(32) NOT NULL COMMENT '订单状态: PENDING, PAID, CANCELLED, REFUNDED',
    `paid_at` DATETIME DEFAULT NULL COMMENT '实际到账支付时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_recharge_user` (`user_id`, `created_at`),
    UNIQUE KEY `uk_paid_trade` (`pay_channel`, `third_party_trade_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户充值订单流水表';

DROP TABLE IF EXISTS `sys_token_usage_ledger`;
CREATE TABLE `sys_token_usage_ledger` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '消费用户ID',
    `run_id` VARCHAR(64) NOT NULL COMMENT '对应的投研执行RunID',
    `model_name` VARCHAR(128) NOT NULL COMMENT '消耗的大模型名称',
    `prompt_tokens` INT NOT NULL COMMENT 'Prompt输入Token数',
    `completion_tokens` INT NOT NULL COMMENT 'Completion输出Token数',
    `total_tokens` INT NOT NULL COMMENT '总Token数',
    `points_deducted` BIGINT NOT NULL COMMENT '本次扣减积分',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '扣减时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_ledger_user_date` (`user_id`, `created_at`),
    KEY `idx_ledger_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token消耗与积分扣减明细账本表';

-- ----------------------------
-- 3. 对话与智能体审计域 (Conversation Domain)
-- ----------------------------

DROP TABLE IF EXISTS `research_conversation`;
CREATE TABLE `research_conversation` (
    `id` VARCHAR(36) NOT NULL COMMENT '会话全局唯一UUID',
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
    `title` VARCHAR(200) NOT NULL COMMENT '会话标题',
    `status` VARCHAR(20) NOT NULL COMMENT '状态: ACTIVE, ARCHIVED, DELETED',
    `last_message_at` DATETIME NOT NULL COMMENT '最新一条消息时间',
    `created_at` DATETIME NOT NULL COMMENT '会话创建时间',
    `updated_at` DATETIME NOT NULL COMMENT '会话更新时间',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '软删除时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_conv_user_time` (`user_id`, `last_message_at`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='金融投研主会话表';

DROP TABLE IF EXISTS `conversation_message`;
CREATE TABLE `conversation_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息自增ID',
    `conversation_id` VARCHAR(36) NOT NULL COMMENT '关联主会话ID',
    `user_id` BIGINT NOT NULL COMMENT '消息归属用户ID',
    `run_id` VARCHAR(36) NOT NULL COMMENT '执行图运行批次RunID',
    `sequence_no` BIGINT NOT NULL COMMENT '会话内单调递增序号',
    `role` VARCHAR(20) NOT NULL COMMENT '消息角色: USER, ASSISTANT',
    `status` VARCHAR(20) NOT NULL COMMENT '状态: RUNNING, COMPLETED, FAILED, CANCELLED',
    `content` LONGTEXT NOT NULL COMMENT '消息正文(支持超长Markdown)',
    `error_code` VARCHAR(80) DEFAULT NULL COMMENT '异常错误码',
    `error_message` VARCHAR(500) DEFAULT NULL COMMENT '异常摘要说明',
    `metadata` JSON NOT NULL COMMENT '元数据扩展(Token统计、耗时等)',
    `created_at` DATETIME NOT NULL COMMENT '创建时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv_seq` (`conversation_id`, `sequence_no`),
    UNIQUE KEY `uk_user_run_role` (`user_id`, `run_id`, `role`),
    KEY `idx_msg_conv_seq` (`conversation_id`, `sequence_no`),
    KEY `idx_msg_user_run` (`user_id`, `run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息历史明细表';

DROP TABLE IF EXISTS `agent_tool_audit`;
CREATE TABLE `agent_tool_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计自增ID',
    `conversation_id` VARCHAR(36) NOT NULL COMMENT '关联主会话ID',
    `assistant_message_id` BIGINT NOT NULL COMMENT '关联助手消息ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `run_id` VARCHAR(36) NOT NULL COMMENT '执行批次RunID',
    `node_id` VARCHAR(128) NOT NULL COMMENT 'DAG图节点ID',
    `agent_name` VARCHAR(128) NOT NULL COMMENT '智能体角色名称',
    `tool_call_id` VARCHAR(200) NOT NULL COMMENT '工具调用唯一ID',
    `tool_name` VARCHAR(200) NOT NULL COMMENT '调用的工具名称',
    `status` VARCHAR(20) NOT NULL COMMENT '状态: RUNNING, SUCCEEDED, FAILED, CANCELLED',
    `arguments` JSON NOT NULL COMMENT '工具输入参数JSON',
    `result_summary` VARCHAR(1000) DEFAULT NULL COMMENT '工具执行结果概览',
    `result_hash` VARCHAR(128) DEFAULT NULL COMMENT '结果SHA256哈希指纹',
    `artifact_ids` JSON NOT NULL COMMENT '工作台产物ArtifactID集合',
    `started_at` DATETIME NOT NULL COMMENT '工具调用开始时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '工具调用完成时间',
    `duration_ms` BIGINT DEFAULT NULL COMMENT '工具调用耗时毫秒',
    `error_code` VARCHAR(80) DEFAULT NULL COMMENT '错误代码',
    `error_message` VARCHAR(500) DEFAULT NULL COMMENT '错误说明',
    `created_at` DATETIME NOT NULL COMMENT '入库时间',
    PRIMARY KEY (`id`),
    KEY `idx_audit_conv_msg` (`conversation_id`, `assistant_message_id`),
    KEY `idx_audit_user_run` (`user_id`, `run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体工具调用轨迹审计表';

-- ----------------------------
-- 4. 会话记忆域 (Memory Domain)
-- ----------------------------

DROP TABLE IF EXISTS `long_term_memory`;
CREATE TABLE `long_term_memory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(255) NOT NULL COMMENT '会话或用户Session标识',
    `content` TEXT NOT NULL COMMENT '长期记忆文本内容',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '沉淀时间',
    PRIMARY KEY (`id`),
    KEY `idx_ltm_session_created` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='长期语义记忆沉淀表';

DROP TABLE IF EXISTS `refined_fact`;
CREATE TABLE `refined_fact` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(255) NOT NULL COMMENT '会话或用户Session标识',
    `type` VARCHAR(50) NOT NULL COMMENT '事实类型',
    `content` TEXT NOT NULL COMMENT '事实要点描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提炼时间',
    PRIMARY KEY (`id`),
    KEY `idx_refined_fact_session_created` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='精炼投研事实沉淀表';

-- ----------------------------
-- 5. 初始化种子数据 (Seed Data)
-- ----------------------------

-- 角色初始化
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`) VALUES
(1, 'ROLE_ADMIN', '超级管理员', '具有系统全量管理与调试权限'),
(2, 'ROLE_ANALYST', '专业分析师', '具备大模型投研分析与深度配置能力'),
(3, 'ROLE_USER', '普通投资者', '标准投研客户端体验权限');

-- 默认测试用户 (密码均为 password123, BCrypt 加密)
INSERT INTO `sys_user` (`id`, `username`, `password_hash`, `mobile`, `email`, `status`) VALUES
(1, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13800000000', 'admin@financialcopilot.com', 'ACTIVE'),
(2, 'analyst_wang', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13800000001', 'wang@financialcopilot.com', 'ACTIVE'),
(3, 'test_investor', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13800000002', 'investor@financialcopilot.com', 'ACTIVE');

-- 绑定用户角色
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES
(1, 1),
(2, 2),
(3, 3);

-- 初始化用户钱包（默认送 500,000 体验算力积分）
INSERT INTO `sys_user_wallet` (`user_id`, `balance_points`, `frozen_points`, `total_recharged_points`, `total_consumed_points`, `version`) VALUES
(1, 10000000, 0, 10000000, 0, 0),
(2, 2000000, 0, 2000000, 0, 0),
(3, 500000, 0, 500000, 0, 0);

-- 初始化投资者画像
INSERT INTO `sys_user_investment_profile` (`user_id`, `risk_level`, `investment_horizon`, `investment_style`, `target_annual_return`, `max_drawdown_tolerance`, `preferred_sectors`, `max_single_position_pct`) VALUES
(1, 'C5', 'LONG_TERM', 'GROWTH', 0.2500, 0.3500, '半导体,人工智能,创新药', 0.2500),
(2, 'C4', 'MEDIUM_TERM', 'BALANCED', 0.1500, 0.2000, '高股息红利,新能源,消费电子', 0.2000),
(3, 'C3', 'MEDIUM_TERM', 'VALUE', 0.1000, 0.1200, '银行,公用事业,沪深300', 0.1500);

-- 初始化大模型计价矩阵
INSERT INTO `sys_model_pricing` (`model_name`, `points_per_thousand_tokens`, `active`) VALUES
('deepseek-chat', 10, 1),
('deepseek-reasoner', 25, 1),
('qwen-plus', 15, 1),
('qwen-max', 35, 1),
('doubao-pro', 12, 1);

-- 初始化充值套餐包
INSERT INTO `sys_recharge_package` (`package_name`, `price_cny`, `points`, `bonus_points`, `is_active`) VALUES
('新手尝鲜包', 19.90, 200000, 20000, 1),
('月度投研专业包', 99.00, 1200000, 200000, 1),
('年度机构尊享包', 999.00, 15000000, 3000000, 1);

SET FOREIGN_KEY_CHECKS = 1;

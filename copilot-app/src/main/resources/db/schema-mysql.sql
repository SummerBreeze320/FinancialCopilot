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
    `user_id` BIGINT NOT NULL COMMENT '所属系统用户ID',
    `nickname` VARCHAR(64) DEFAULT NULL COMMENT '用户个性化昵称',
    `avatar_url` VARCHAR(512) DEFAULT NULL COMMENT '头像地址',
    `company` VARCHAR(128) DEFAULT NULL COMMENT '任职所属金融机构或企业单位',
    `occupation` VARCHAR(128) DEFAULT NULL COMMENT '职业头衔/岗位名称',
    `bio` TEXT DEFAULT NULL COMMENT '个人简介与研究专长描述',
    `city` VARCHAR(64) DEFAULT NULL COMMENT '所在常驻城市',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户基础个人资料表';

DROP TABLE IF EXISTS `sys_user_investment_profile`;
CREATE TABLE `sys_user_investment_profile` (
    `user_id` BIGINT NOT NULL COMMENT '所属系统用户ID',
    `risk_tolerance_level` VARCHAR(20) NOT NULL DEFAULT 'C3' COMMENT '适格风险承受能力评级: C1~C5',
    `investment_horizon` VARCHAR(32) DEFAULT 'MEDIUM_TERM' COMMENT '计划投资期限偏好',
    `preferred_asset_classes` VARCHAR(512) DEFAULT '[]' COMMENT '偏好大类资产JSON数组',
    `preferred_sectors` VARCHAR(512) DEFAULT '[]' COMMENT '偏好板块/行业主题JSON数组',
    `max_drawdown_tolerance` DECIMAL(10, 4) DEFAULT 15.0000 COMMENT '最大可承受回撤比例(%)',
    `target_annual_return` DECIMAL(10, 4) DEFAULT 12.0000 COMMENT '目标年化收益率期望(%)',
    `investment_style` VARCHAR(32) DEFAULT 'BALANCED' COMMENT '投资风格偏好: VALUE, GROWTH, BALANCED',
    `single_position_limit` DECIMAL(10, 4) DEFAULT 20.0000 COMMENT '单只标的最高仓位上限限制(%)',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户投资画像与风险偏好表';

DROP TABLE IF EXISTS `sys_user_identity`;
CREATE TABLE `sys_user_identity` (
    `user_id` BIGINT NOT NULL COMMENT '所属系统用户ID',
    `real_name` VARCHAR(64) NOT NULL COMMENT '认证真实姓名',
    `id_card_type` VARCHAR(32) NOT NULL DEFAULT 'ID_CARD' COMMENT '证件类型: ID_CARD, PASSPORT',
    `id_card_hash` VARCHAR(128) NOT NULL COMMENT '证件号SHA256哈希防重值',
    `id_card_encrypted` VARCHAR(256) NOT NULL COMMENT 'AES-256对称加密存储证件密文',
    `id_card_masked` VARCHAR(32) DEFAULT NULL COMMENT '掩码脱敏展示证件号',
    `verify_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '实名认证审核状态: PENDING, APPROVED, REJECTED',
    `reject_reason` VARCHAR(256) DEFAULT NULL COMMENT '审核驳回原因说明',
    `verified_at` DATETIME DEFAULT NULL COMMENT '实名认证审核完成时间戳',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '认证申请提交时间戳',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_id_card_hash` (`id_card_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='金融合规实名认证身份表';

DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色自增ID',
    `role_code` VARCHAR(64) NOT NULL COMMENT '角色唯一代码: ROLE_ADMIN, ROLE_ANALYST, ROLE_USER',
    `role_name` VARCHAR(64) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(256) DEFAULT NULL COMMENT '角色说明',
    `is_system` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为系统内置角色(内置不可删除)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限自增ID',
    `perm_code` VARCHAR(64) NOT NULL COMMENT '权限唯一编码(如 fund:read, fund:execute)',
    `perm_name` VARCHAR(64) NOT NULL COMMENT '权限显示名称',
    `resource_type` VARCHAR(32) NOT NULL DEFAULT 'API' COMMENT '资源类型: API, MENU, BUTTON',
    `path` VARCHAR(256) DEFAULT NULL COMMENT '资源路径/Ant表达式',
    `method` VARCHAR(16) DEFAULT NULL COMMENT 'HTTP方法: GET, POST, PUT, DELETE, *',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限点表';

DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
    `user_id` BIGINT NOT NULL COMMENT '系统用户ID',
    `role_id` BIGINT NOT NULL COMMENT '所属角色ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
    `role_id` BIGINT NOT NULL COMMENT '所属角色ID',
    `permission_id` BIGINT NOT NULL COMMENT '关联权限点ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';


-- ----------------------------
-- 3. 对话与智能体审计域 (Conversation Domain)
-- ----------------------------

DROP TABLE IF EXISTS `research_conversation`;
CREATE TABLE `research_conversation` (
    `id` VARCHAR(36) NOT NULL COMMENT '会话全局唯一UUID',
    `user_id` BIGINT NOT NULL COMMENT '所属系统用户ID',
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
    `conversation_id` VARCHAR(36) NOT NULL COMMENT '关联主会话UUID',
    `user_id` BIGINT NOT NULL COMMENT '消息归属用户ID',
    `run_id` VARCHAR(36) NOT NULL COMMENT '执行图运行批次RunID',
    `sequence_no` BIGINT NOT NULL COMMENT '会话内单调递增序号',
    `role` VARCHAR(20) NOT NULL COMMENT '消息角色: USER, ASSISTANT, SYSTEM',
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
    `conversation_id` VARCHAR(36) NOT NULL COMMENT '关联主会话UUID',
    `assistant_message_id` BIGINT NOT NULL COMMENT '关联助手消息ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `run_id` VARCHAR(36) NOT NULL COMMENT '执行批次RunID',
    `node_id` VARCHAR(128) NOT NULL COMMENT 'DAG图节点ID',
    `agent_name` VARCHAR(128) NOT NULL COMMENT '智能体角色名称',
    `tool_call_id` VARCHAR(200) NOT NULL COMMENT '工具调用唯一ID',
    `tool_name` VARCHAR(200) NOT NULL COMMENT '调用的工具名称',
    `status` VARCHAR(20) NOT NULL COMMENT '状态: RUNNING, SUCCESS, FAILED, CANCELLED',
    `arguments` JSON NOT NULL COMMENT '工具输入参数JSON',
    `result_summary` VARCHAR(1000) DEFAULT NULL COMMENT '工具执行结果概览',
    `result_hash` VARCHAR(128) DEFAULT NULL COMMENT '结果SHA256哈希指纹',
    `artifact_ids` JSON NOT NULL COMMENT '工作台产物ArtifactID集合',
    `started_at` DATETIME NOT NULL COMMENT '工具调用开始时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '工具调用完成时间',
    `duration_ms` BIGINT DEFAULT NULL COMMENT '工具调用耗时毫秒',
    `error_code` VARCHAR(80) DEFAULT NULL COMMENT '错误代码',
    `error_message` VARCHAR(500) DEFAULT NULL COMMENT '错误说明',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_run_tool` (`user_id`, `run_id`, `tool_call_id`),
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
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`, `is_system`) VALUES
(1, 'ROLE_ADMIN', '超级管理员', '具有系统全量管理与调试权限', 1),
(2, 'ROLE_ANALYST', '专业分析师', '具备大模型投研分析与深度配置能力', 1),
(3, 'ROLE_USER', '普通投资者', '标准投研客户端体验权限', 1);

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


-- 初始化投资者画像
INSERT INTO `sys_user_investment_profile` (`user_id`, `risk_tolerance_level`, `investment_horizon`, `preferred_asset_classes`, `preferred_sectors`, `max_drawdown_tolerance`, `target_annual_return`, `investment_style`, `single_position_limit`) VALUES
(1, 'C5', 'LONG_TERM', '["EQUITY","FUTURES"]', '["半导体","人工智能","创新药"]', 35.0000, 25.0000, 'GROWTH', 25.0000),
(2, 'C4', 'MEDIUM_TERM', '["EQUITY","BOND"]', '["高股息红利","新能源","消费电子"]', 20.0000, 15.0000, 'BALANCED', 20.0000),
(3, 'C3', 'MEDIUM_TERM', '["BOND","MONEY_MARKET"]', '["银行","公用事业","沪深300"]', 12.0000, 10.0000, 'VALUE', 15.0000);


SET FOREIGN_KEY_CHECKS = 1;

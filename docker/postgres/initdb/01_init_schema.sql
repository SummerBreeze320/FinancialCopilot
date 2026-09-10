-- ==============================================================================
-- 金融研究 Agent (FinancialCopilot) 核心数据库初始化脚本
-- 引擎: PostgreSQL 16 + PGVector
-- ==============================================================================

-- 1. 启用 pgvector 向量扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 基金管理公司表
CREATE TABLE IF NOT EXISTS fund_company (
    company_id VARCHAR(20) PRIMARY KEY,
    company_name VARCHAR(100) NOT NULL,
    short_name VARCHAR(50),
    establishment_date DATE,
    total_scale_billion NUMERIC(10, 2) DEFAULT 0.00,       -- 非货管理总规模 (亿元)
    equity_scale_billion NUMERIC(10, 2) DEFAULT 0.00,      -- 权益类管理规模 (亿元)
    manager_count INT DEFAULT 0,                           -- 旗下经理人数
    fund_count INT DEFAULT 0,                              -- 旗下产品数量
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 基金经理档案表
CREATE TABLE IF NOT EXISTS fund_manager (
    manager_id VARCHAR(20) PRIMARY KEY,
    manager_name VARCHAR(50) NOT NULL,
    company_id VARCHAR(20) REFERENCES fund_company(company_id),
    gender VARCHAR(10),
    education VARCHAR(20),                                 -- 学历: 硕士/博士/学士
    working_days INT DEFAULT 0,                            -- 证券投资管理累计天数
    current_total_scale_billion NUMERIC(10, 2) DEFAULT 0.00, -- 在管总规模 (亿元)
    best_fund_code VARCHAR(10),                            -- 代表作基金代码
    best_fund_return NUMERIC(8, 2),                        -- 代表作任职年化回报 (%)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. 基金基础信息表
CREATE TABLE IF NOT EXISTS fund_info (
    fund_code VARCHAR(10) PRIMARY KEY,
    fund_name VARCHAR(100) NOT NULL,
    fund_type VARCHAR(50) NOT NULL,                        -- 股票型 / 偏股混合型 / 债券型 / 指数型
    establishment_date DATE NOT NULL,
    management_company_id VARCHAR(20) REFERENCES fund_company(company_id),
    current_scale_billion NUMERIC(10, 2) DEFAULT 0.00,     -- 最新净资产规模 (亿元)
    tracking_benchmark TEXT,                               -- 业绩比较基准
    custodian_bank VARCHAR(100),                           -- 托管行
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 5. 基金经理-基金任职历史关联表
CREATE TABLE IF NOT EXISTS fund_manager_mapping (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) REFERENCES fund_info(fund_code),
    manager_id VARCHAR(20) REFERENCES fund_manager(manager_id),
    start_date DATE NOT NULL,
    end_date DATE,                                         -- NULL 表示当前在任
    is_current BOOLEAN DEFAULT TRUE,
    tenure_return NUMERIC(8, 2)                            -- 该任期累计回报 (%)
);

CREATE INDEX IF NOT EXISTS idx_mgr_map_fund ON fund_manager_mapping(fund_code);
CREATE INDEX IF NOT EXISTS idx_mgr_map_mgr ON fund_manager_mapping(manager_id);

-- 6. 基金每日复权净值时序表 (用于夏普、最大回撤、波动率等量化计算)
CREATE TABLE IF NOT EXISTS fund_nav_history (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) NOT NULL,
    nav_date DATE NOT NULL,
    unit_nav NUMERIC(10, 4) NOT NULL,                      -- 单位净值
    accumulated_nav NUMERIC(10, 4) NOT NULL,               -- 累计净值
    adjusted_nav NUMERIC(10, 4) NOT NULL,                  -- 复权净值
    daily_growth_rate NUMERIC(8, 4),                       -- 日涨跌幅 (%)
    CONSTRAINT uk_fund_nav_date UNIQUE(fund_code, nav_date)
);

CREATE INDEX IF NOT EXISTS idx_nav_fund_date ON fund_nav_history(fund_code, nav_date DESC);

-- 7. 基金季度前十大重仓明细表 (用于持仓穿透、集中度、行业风格分析)
CREATE TABLE IF NOT EXISTS fund_quarterly_holdings (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) NOT NULL,
    report_quarter VARCHAR(10) NOT NULL,                   -- 例如: '2024Q2'
    rank_order INT NOT NULL,                               -- 持仓序号 1-10
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100) NOT NULL,
    holding_ratio NUMERIC(6, 2) NOT NULL,                  -- 占净值比例 (%)
    holding_shares_ten_thousand NUMERIC(12, 2),            -- 持股数 (万股)
    holding_sector VARCHAR(50) NOT NULL,                   -- 行业分类 (申万一级板块)
    CONSTRAINT uk_fund_quarter_stock UNIQUE(fund_code, report_quarter, stock_code)
);

CREATE INDEX IF NOT EXISTS idx_holdings_fund_quarter ON fund_quarterly_holdings(fund_code, report_quarter);

-- 8. 基金定期报告定性文本向量库 (PGVector 混合 RAG 检索)
CREATE TABLE IF NOT EXISTS fund_report_vector (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) NOT NULL,
    manager_name VARCHAR(50),
    report_quarter VARCHAR(10) NOT NULL,
    section_title VARCHAR(100) NOT NULL,                   -- 如: "管理人对报告期内投资策略与运作分析"
    content TEXT NOT NULL,                                 -- 文本段落切片
    embedding vector(1536),                                -- 对应 DeepSeek/OpenAI Embedding 维度
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建 HNSW 向量索引 (极大提升相似度召回效率)
CREATE INDEX IF NOT EXISTS idx_fund_report_vector_hnsw 
ON fund_report_vector USING hnsw (embedding vector_cosine_ops);

-- ==============================================================================
-- 9. 股票基础信息表 (多金融兼容扩展)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS stock_info (
    stock_code VARCHAR(20) PRIMARY KEY,
    stock_name VARCHAR(100) NOT NULL,
    exchange VARCHAR(20) NOT NULL,                         -- SSE / SZSE / BSE
    industry VARCHAR(50),                                  -- 申万一级行业
    pe_ttm NUMERIC(10, 2),                                 -- 市盈率 TTM
    pb NUMERIC(10, 2),                                     -- 市净率
    market_cap_billion NUMERIC(12, 2) DEFAULT 0.00,        -- 总市值 (亿元)
    roe NUMERIC(8, 2),                                     -- 净资产收益率 (%)
    dividend_yield NUMERIC(8, 2),                          -- 股息率 (%)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_stock_industry ON stock_info(industry);

-- ==============================================================================
-- 10. 商业化运营与 Token 计量计费中心表
-- ==============================================================================

-- 10.1 用户算力钱包表 (1 元人民币 = 10,000 智算点)
CREATE TABLE IF NOT EXISTS sys_user_wallet (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    tenant_id VARCHAR(50) DEFAULT 'DEFAULT',
    balance_points BIGINT DEFAULT 0,                       -- 可用算力点余额
    frozen_points BIGINT DEFAULT 0,                        -- 投研并发冻结占用点数
    total_recharged_points BIGINT DEFAULT 0,               -- 累计充值点数
    total_consumed_points BIGINT DEFAULT 0,                -- 累计消费点数
    wallet_status VARCHAR(20) DEFAULT 'NORMAL',            -- NORMAL(正常), ARREARS(欠费), FROZEN(冻结)
    version BIGINT DEFAULT 0,                              -- 乐观锁版本号
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wallet_user ON sys_user_wallet(user_id);

-- 10.2 大模型阶梯定价规格表
CREATE TABLE IF NOT EXISTS llm_model_pricing (
    id BIGSERIAL PRIMARY KEY,
    provider_type VARCHAR(50) NOT NULL,                    -- DEEPSEEK, OPENAI, QWEN, ZHIPU, OLLAMA
    model_name VARCHAR(100) NOT NULL UNIQUE,               -- deepseek-chat, deepseek-reasoner, gpt-4o 等
    input_price_per_k NUMERIC(10, 4) NOT NULL,             -- 每千 Token 输入单价 (点)
    output_price_per_k NUMERIC(10, 4) NOT NULL,            -- 每千 Token 输出单价 (点)
    cache_hit_price_per_k NUMERIC(10, 4) DEFAULT 0.00,     -- 每千 Token 缓存命中优惠价 (点)
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 10.3 Token 消费对账明细流水表
CREATE TABLE IF NOT EXISTS llm_token_usage_ledger (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64),                                -- 任务/会话跟踪 ID
    task_type VARCHAR(50) NOT NULL,                        -- SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    consumed_points BIGINT NOT NULL DEFAULT 0,             -- 本次扣减智算点数
    latency_ms INT DEFAULT 0,                              -- 端到端模型响应耗时 (ms)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ledger_user_time ON llm_token_usage_ledger(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_session ON llm_token_usage_ledger(session_id);

-- 10.4 充值规格套餐表
CREATE TABLE IF NOT EXISTS sys_recharge_package (
    id BIGSERIAL PRIMARY KEY,
    package_name VARCHAR(100) NOT NULL,
    price_cny NUMERIC(10, 2) NOT NULL,                     -- 售价人民币 (元)
    granted_points BIGINT NOT NULL,                        -- 基础算力点数
    bonus_points BIGINT DEFAULT 0,                         -- 赠送点数
    badge VARCHAR(50),                                     -- 营销角标
    sort_order INT DEFAULT 0,                              -- 排序权重
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 10.5 充值交易订单表
CREATE TABLE IF NOT EXISTS sys_recharge_order (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,                  -- 唯一业务订单号
    user_id BIGINT NOT NULL,
    package_id BIGINT REFERENCES sys_recharge_package(id),
    pay_amount_cny NUMERIC(10, 2) NOT NULL,
    target_points BIGINT NOT NULL,                         -- 最终到账点数
    pay_channel VARCHAR(30) NOT NULL,                      -- WECHAT, ALIPAY, BANK
    order_status VARCHAR(20) DEFAULT 'PENDING',            -- PENDING, PAID, CANCELLED
    third_party_trade_no VARCHAR(100),                     -- 第三方流水凭证
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_order_user ON sys_recharge_order(user_id, created_at DESC);

-- ==============================================================================
-- 11. 初始业务种子数据 (Seed Data)
-- ==============================================================================

-- 预置大模型定价矩阵
INSERT INTO llm_model_pricing (provider_type, model_name, input_price_per_k, output_price_per_k, cache_hit_price_per_k, is_active)
VALUES 
    ('DEEPSEEK', 'deepseek-chat', 10.0, 20.0, 2.0, true),
    ('DEEPSEEK', 'deepseek-reasoner', 40.0, 160.0, 10.0, true),
    ('OPENAI', 'gpt-4o-mini', 15.0, 60.0, 7.5, true),
    ('OPENAI', 'gpt-4o', 250.0, 1000.0, 125.0, true),
    ('OPENAI', 'o1', 300.0, 1200.0, 150.0, true),
    ('QWEN', 'qwen-plus', 8.0, 20.0, 2.0, true)
ON CONFLICT (model_name) DO NOTHING;

-- 预置在线充值规格套餐
INSERT INTO sys_recharge_package (id, package_name, price_cny, granted_points, bonus_points, badge, sort_order, is_active)
VALUES 
    (1, '投研尝鲜包', 49.00, 500000, 0, '入门推荐', 1, true),
    (2, '专业分析师包', 199.00, 2000000, 200000, '热销首选 (送10%)', 2, true),
    (3, '机构进阶包', 599.00, 6000000, 1200000, '超值加赠 (送20%)', 3, true),
    (4, '企业旗舰包', 2999.00, 30000000, 9000000, '尊享 1V1 投研支持', 4, true)
ON CONFLICT (id) DO NOTHING;

-- 预置体验用户钱包 (User 1 初始赠送 100,000 点数)
INSERT INTO sys_user_wallet (user_id, balance_points, total_recharged_points, wallet_status, version)
VALUES (1, 100000, 100000, 'NORMAL', 0)
ON CONFLICT (user_id) DO NOTHING;

-- ==============================================================================
-- 12. 用户、金融实名认证与 RBAC 权限中心
-- ==============================================================================

-- 12.1 系统用户基础表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    mobile VARCHAR(20) UNIQUE,
    email VARCHAR(100) UNIQUE,
    status VARCHAR(20) DEFAULT 'ACTIVE',                -- ACTIVE(正常), LOCKED(锁定), DISABLED(禁用)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_username ON sys_user(username);

-- 12.2 系统角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGSERIAL PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL UNIQUE,              -- ROLE_ADMIN, ROLE_ANALYST, ROLE_USER
    role_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    is_system BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 12.3 系统权限点表
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGSERIAL PRIMARY KEY,
    perm_code VARCHAR(100) NOT NULL UNIQUE,             -- research:chat, research:thinking, billing:recharge, admin:llm:config
    perm_name VARCHAR(100) NOT NULL,
    resource_type VARCHAR(20) DEFAULT 'API',
    path VARCHAR(200),
    method VARCHAR(10),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 12.4 用户-角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_role UNIQUE(user_id, role_id)
);

-- 12.5 角色-权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES sys_permission(id) ON DELETE CASCADE,
    CONSTRAINT uk_role_perm UNIQUE(role_id, permission_id)
);

-- 12.6 用户个人资料表
CREATE TABLE IF NOT EXISTS sys_user_profile (
    user_id BIGINT PRIMARY KEY REFERENCES sys_user(id) ON DELETE CASCADE,
    nickname VARCHAR(50),
    avatar_url VARCHAR(255),
    company VARCHAR(100),
    occupation VARCHAR(100),
    bio VARCHAR(255),
    city VARCHAR(50),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 12.7 金融实名认证表 (KYC)
CREATE TABLE IF NOT EXISTS sys_user_identity (
    user_id BIGINT PRIMARY KEY REFERENCES sys_user(id) ON DELETE CASCADE,
    real_name VARCHAR(50) NOT NULL,
    id_card_type VARCHAR(20) DEFAULT 'ID_CARD',         -- ID_CARD, PASSPORT, HK_MACAO_PASS
    id_card_hash VARCHAR(64) NOT NULL UNIQUE,           -- 身份证 SHA-256 哈希判重
    id_card_encrypted VARCHAR(255) NOT NULL,            -- 对称加密密文
    id_card_masked VARCHAR(30) NOT NULL,               -- 前端脱敏显示 (如: 110101********1234)
    verify_status VARCHAR(20) DEFAULT 'PENDING',        -- UNVERIFIED, PENDING, VERIFIED, REJECTED
    reject_reason VARCHAR(255),
    verified_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 12.8 用户投资画像与偏好表 (投研个性化底座)
CREATE TABLE IF NOT EXISTS sys_user_investment_profile (
    user_id BIGINT PRIMARY KEY REFERENCES sys_user(id) ON DELETE CASCADE,
    risk_tolerance_level VARCHAR(20) DEFAULT 'C3',      -- C1(保守型), C2(相对保守型), C3(平衡型), C4(相对积极型), C5(进取型)
    investment_horizon VARCHAR(20) DEFAULT 'MEDIUM_TERM',-- SHORT_TERM(<1年), MEDIUM_TERM(1-3年), LONG_TERM(>3年)
    preferred_asset_classes TEXT,                       -- JSON 数组，如 ["FUND", "STOCK"]
    preferred_sectors TEXT,                             -- JSON 数组，如 ["医药生物", "半导体芯片", "大消费"]
    max_drawdown_tolerance NUMERIC(5, 2) DEFAULT 15.00, -- 最大承受回撤 (%)
    target_annual_return NUMERIC(5, 2) DEFAULT 12.00,   -- 目标年化收益率 (%)
    investment_style VARCHAR(30) DEFAULT 'BALANCED',    -- VALUE, GROWTH, BALANCED, DIVIDEND
    single_position_limit NUMERIC(5, 2) DEFAULT 20.00,  -- 单标的持仓上限比例 (%)
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 预置基础角色
INSERT INTO sys_role (id, role_code, role_name, description, is_system)
VALUES 
    (1, 'ROLE_ADMIN', '平台研发管理员', '拥有大模型参数热切换、实名审批与用户全量管理特权', true),
    (2, 'ROLE_ANALYST', '专业机构分析师', '拥有深度思考推理、批量对标与高并发投研特权', true),
    (3, 'ROLE_USER', '个人注册投资者', '拥有标准投研检索问答与基础点数钱包功能', true)
ON CONFLICT (id) DO NOTHING;

-- 预置基础权限
INSERT INTO sys_permission (id, perm_code, perm_name, resource_type)
VALUES
    (1, 'research:chat', '标准投研问答', 'API'),
    (2, 'research:thinking', '深度思考推理推演', 'API'),
    (3, 'billing:recharge', '算力点数充值', 'API'),
    (4, 'admin:llm:config', '大模型动态热切换', 'API'),
    (5, 'admin:user:manage', '用户与实名风控管理', 'API')
ON CONFLICT (id) DO NOTHING;

-- 预置角色权限
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5),
    (2, 1), (2, 2), (2, 3),
    (3, 1), (3, 3)
ON CONFLICT DO NOTHING;

-- 预置系统管理员与演示用户 (初始密码均为 123456，BCrypt 哈希为 $2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2)
INSERT INTO sys_user (id, username, password_hash, mobile, email, status)
VALUES
    (1, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13800000001', 'admin@financialcopilot.com', 'ACTIVE'),
    (2, 'analyst', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13800000002', 'analyst@fund.com', 'ACTIVE'),
    (3, 'investor', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '13800000003', 'investor@qq.com', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- 关联用户角色
INSERT INTO sys_user_role (user_id, role_id) VALUES
    (1, 1),
    (2, 2),
    (3, 3)
ON CONFLICT DO NOTHING;

-- 预置用户资料
INSERT INTO sys_user_profile (user_id, nickname, avatar_url, company, occupation, bio, city)
VALUES
    (1, '平台超管', 'https://avatar.vercel.sh/admin', 'FinancialCopilot', '算法架构师', '系统首席架构', '上海'),
    (2, '王牌分析师', 'https://avatar.vercel.sh/analyst', '中欧基金', '公募资深研究员', '专注医药与大健康成长股挖掘', '深圳'),
    (3, '价值投资者', 'https://avatar.vercel.sh/investor', '个人投资', '独立投资人', '寻找安全边际与稳健复利', '北京')
ON CONFLICT (user_id) DO NOTHING;

-- 预置用户投资画像 (为 User 2 & 3 分别设置 C4 进取型与 C3 平衡型)
INSERT INTO sys_user_investment_profile (user_id, risk_tolerance_level, investment_horizon, preferred_asset_classes, preferred_sectors, max_drawdown_tolerance, target_annual_return, investment_style, single_position_limit)
VALUES
    (1, 'C5', 'LONG_TERM', '["FUND","STOCK","FUTURES"]', '["人工智能","半导体芯片","算力硬件"]', 25.00, 20.00, 'GROWTH', 30.00),
    (2, 'C4', 'MEDIUM_TERM', '["FUND","STOCK"]', '["医药生物","医疗器械","创新药"]', 18.00, 15.00, 'GROWTH', 25.00),
    (3, 'C3', 'LONG_TERM', '["FUND"]', '["大消费","红利低波","金融地产"]', 12.00, 10.00, 'BALANCED', 20.00)
ON CONFLICT (user_id) DO NOTHING;



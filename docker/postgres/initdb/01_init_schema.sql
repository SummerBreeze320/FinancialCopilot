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
-- 9. 股票基础信息表 (多金融产品兼容扩展)
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

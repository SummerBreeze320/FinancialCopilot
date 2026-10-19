-- ==============================================================================
-- 股票深度数据表 (财务季报/分红历史/十大股东/研报向量)
-- 引擎: PostgreSQL 16 + PGVector
-- ==============================================================================

-- 1. 股票季度核心财务指标表 (利润表/资产负债表/现金流关键指标)
CREATE TABLE IF NOT EXISTS stock_financial_quarterly (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    report_quarter VARCHAR(10) NOT NULL,                   -- 例如: '2024Q2', '2024Q3'
    report_date DATE,                                      -- 报告期截止日

    -- 利润表核心
    revenue_billion NUMERIC(14, 2),                        -- 营业收入 (亿元)
    revenue_yoy NUMERIC(10, 4),                            -- 营收同比增长率 (%)
    net_profit_billion NUMERIC(14, 2),                     -- 归母净利润 (亿元)
    net_profit_yoy NUMERIC(10, 4),                         -- 归母净利润同比增长率 (%)
    deducted_net_profit_billion NUMERIC(14, 2),            -- 扣非归母净利润 (亿元)
    eps NUMERIC(10, 4),                                    -- 基本每股收益 (元)
    diluted_eps NUMERIC(10, 4),                            -- 稀释每股收益 (元)

    -- 盈利能力
    gross_margin NUMERIC(10, 4),                           -- 销售毛利率 (%)
    net_margin NUMERIC(10, 4),                             -- 销售净利率 (%)
    roe NUMERIC(10, 4),                                    -- 加权净资产收益率 (%)
    roa NUMERIC(10, 4),                                    -- 总资产收益率 (%)
    roic NUMERIC(10, 4),                                   -- 投入资本回报率 (%)

    -- 资产负债表核心
    total_assets_billion NUMERIC(14, 2),                   -- 总资产 (亿元)
    net_assets_billion NUMERIC(14, 2),                     -- 归母净资产 (亿元)
    debt_to_asset_ratio NUMERIC(10, 4),                    -- 资产负债率 (%)
    current_ratio NUMERIC(10, 4),                          -- 流动比率
    quick_ratio NUMERIC(10, 4),                            -- 速动比率

    -- 现金流核心
    operating_cashflow_billion NUMERIC(14, 2),             -- 经营活动现金流净额 (亿元)
    free_cashflow_billion NUMERIC(14, 2),                  -- 自由现金流 (亿元)

    -- 每股指标
    bps NUMERIC(10, 4),                                    -- 每股净资产 (元)
    undistributed_eps NUMERIC(10, 4),                      -- 每股未分配利润 (元)

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stock_financial_quarter UNIQUE(stock_code, report_quarter)
);

CREATE INDEX IF NOT EXISTS idx_financial_stock_date ON stock_financial_quarterly(stock_code, report_quarter DESC);

-- 2. 股票分红送转历史表
CREATE TABLE IF NOT EXISTS stock_dividend_history (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    announcement_date DATE,                                -- 公告日
    record_date DATE,                                      -- 股权登记日
    ex_dividend_date DATE,                                 -- 除权除息日
    dividend_year VARCHAR(10),                             -- 分红年度 (如: '2023')

    -- 现金分红
    cash_dividend_per_10 NUMERIC(10, 4),                   -- 每10股派现 (元, 含税)
    bonus_shares_per_10 NUMERIC(10, 4),                    -- 每10股送股 (股)
    transfer_shares_per_10 NUMERIC(10, 4),                 -- 每10股转增 (股)

    -- 衍生计算
    dividend_ratio NUMERIC(10, 4),                         -- 股息率 (%)
    payout_ratio NUMERIC(10, 4),                           -- 分红率 (股利支付率, %)

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stock_dividend UNIQUE(stock_code, dividend_year, ex_dividend_date)
);

CREATE INDEX IF NOT EXISTS idx_dividend_stock ON stock_dividend_history(stock_code);
CREATE INDEX IF NOT EXISTS idx_dividend_year ON stock_dividend_history(dividend_year);

-- 3. 股票十大股东季度明细表
CREATE TABLE IF NOT EXISTS stock_top_shareholders (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    report_quarter VARCHAR(10) NOT NULL,                   -- 例如: '2024Q2'
    rank_order INT NOT NULL,                               -- 股东排名 1-10
    shareholder_name VARCHAR(200) NOT NULL,                -- 股东名称
    shareholder_type VARCHAR(30),                          -- 机构/个人/基金/QFII/社保/保险
    holding_shares_ten_thousand NUMERIC(14, 2),            -- 持股数量 (万股)
    holding_ratio NUMERIC(8, 4),                           -- 持股比例 (%)
    holding_change VARCHAR(20),                            -- 变动情况: 增持/减持/新进/不变/退出
    holding_market_value_billion NUMERIC(14, 2),           -- 持股市值 (亿元)

    CONSTRAINT uk_stock_shareholder_quarter UNIQUE(stock_code, report_quarter, rank_order)
);

CREATE INDEX IF NOT EXISTS idx_shareholder_stock ON stock_top_shareholders(stock_code, report_quarter);

-- 4. 股票研报/公告定性文本向量库 (PGVector 混合 RAG 检索)
CREATE TABLE IF NOT EXISTS stock_research_vector (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    source_type VARCHAR(30) NOT NULL,                      -- ANALYST_REPORT / COMPANY_ANNOUNCEMENT / EARNINGS_CALL / NEWS
    title VARCHAR(200) NOT NULL,                           -- 标题
    author VARCHAR(100),                                   -- 作者/机构
    publish_date DATE,                                     -- 发布日期
    content TEXT NOT NULL,                                 -- 文本段落切片
    embedding vector(1024),                                -- qwen3-embedding 1024 维向量
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_research_stock ON stock_research_vector(stock_code);
CREATE INDEX IF NOT EXISTS idx_research_source ON stock_research_vector(source_type);
CREATE INDEX IF NOT EXISTS idx_research_embedding_hnsw
ON stock_research_vector USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

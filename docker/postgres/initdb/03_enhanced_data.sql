-- ==============================================================================
-- 增强数据表 (指数行情/基金业绩/股票K线/宏观指标)
-- ==============================================================================

-- 1. 市场指数基础信息表
CREATE TABLE IF NOT EXISTS market_index_info (
    index_code VARCHAR(20) PRIMARY KEY,
    index_name VARCHAR(50) NOT NULL,
    exchange VARCHAR(10) NOT NULL,
    category VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. 市场指数日线时序表
CREATE TABLE IF NOT EXISTS market_index_daily (
    id BIGSERIAL PRIMARY KEY,
    index_code VARCHAR(20) NOT NULL REFERENCES market_index_info(index_code),
    trade_date DATE NOT NULL,
    open NUMERIC(12, 4),
    high NUMERIC(12, 4),
    low NUMERIC(12, 4),
    close NUMERIC(12, 4) NOT NULL,
    pct_chg NUMERIC(10, 4),
    volume NUMERIC(20, 2),
    amount NUMERIC(20, 2),
    CONSTRAINT uk_index_date UNIQUE(index_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_index_daily_date ON market_index_daily(index_code, trade_date DESC);

-- 3. 基金业绩指标表
CREATE TABLE IF NOT EXISTS fund_performance (
    fund_code VARCHAR(10) PRIMARY KEY REFERENCES fund_info(fund_code),
    return_1m NUMERIC(10, 4),
    return_3m NUMERIC(10, 4),
    return_6m NUMERIC(10, 4),
    return_1y NUMERIC(10, 4),
    return_3y NUMERIC(10, 4),
    return_ytd NUMERIC(10, 4),
    return_since_inception NUMERIC(10, 4),
    sharpe_ratio NUMERIC(10, 4),
    max_drawdown NUMERIC(10, 4),
    volatility NUMERIC(10, 4),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. 股票日K线数据表
CREATE TABLE IF NOT EXISTS stock_daily_kline (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    trade_date DATE NOT NULL,
    open NUMERIC(12, 4) NOT NULL,
    high NUMERIC(12, 4) NOT NULL,
    low NUMERIC(12, 4) NOT NULL,
    close NUMERIC(12, 4) NOT NULL,
    volume NUMERIC(20, 2),
    amount NUMERIC(20, 2),
    CONSTRAINT uk_stock_date UNIQUE(stock_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_kline_stock_date ON stock_daily_kline(stock_code, trade_date DESC);

-- 5. 宏观经济指标表
CREATE TABLE IF NOT EXISTS macro_indicator (
    id BIGSERIAL PRIMARY KEY,
    indicator_code VARCHAR(30) NOT NULL,
    indicator_name VARCHAR(50) NOT NULL,
    category VARCHAR(20),
    unit VARCHAR(10),
    frequency VARCHAR(10),
    report_date DATE NOT NULL,
    value NUMERIC(15, 4),
    CONSTRAINT uk_macro_code_date UNIQUE(indicator_code, report_date)
);

CREATE INDEX IF NOT EXISTS idx_macro_category_date ON macro_indicator(category, report_date DESC);

-- 6. 基金扩展基础信息表
CREATE TABLE IF NOT EXISTS fund_detail (
    fund_code VARCHAR(10) PRIMARY KEY REFERENCES fund_info(fund_code),
    fund_name VARCHAR(100) NOT NULL,
    invest_type VARCHAR(50),
    net_asset_yuan NUMERIC(18, 2) DEFAULT 0.00,
    setup_date DATE,
    latest_nav NUMERIC(10, 4),
    latest_acc_nav NUMERIC(10, 4),
    latest_nav_date DATE,
    invest_style VARCHAR(50),
    risk_level VARCHAR(20),
    invest_strategy TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fund_detail_style ON fund_detail(invest_style);
CREATE INDEX IF NOT EXISTS idx_fund_detail_risk ON fund_detail(risk_level);

-- 7. 基金资产配置表 (多季度股票/债券市值)
CREATE TABLE IF NOT EXISTS fund_asset_allocation (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) NOT NULL REFERENCES fund_info(fund_code),
    report_quarter VARCHAR(10) NOT NULL,
    stock_value NUMERIC(18, 2) DEFAULT 0.00,           -- 股票市值 (元)
    bond_value NUMERIC(18, 2) DEFAULT 0.00,             -- 债券市值 (元)
    CONSTRAINT uk_fund_alloc_quarter UNIQUE(fund_code, report_quarter)
);

CREATE INDEX IF NOT EXISTS idx_fund_alloc_quarter ON fund_asset_allocation(report_quarter);

-- 8. 基金行业配置表 (从持仓汇总)
CREATE TABLE IF NOT EXISTS fund_industry_allocation (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) NOT NULL REFERENCES fund_info(fund_code),
    report_quarter VARCHAR(10) NOT NULL,
    industry VARCHAR(50) NOT NULL,
    holding_ratio NUMERIC(8, 2) NOT NULL,               -- 合计持仓比例 (%)
    ratio_pct NUMERIC(8, 2) NOT NULL,                   -- 占前十大比例 (%)
    CONSTRAINT uk_fund_ind_quarter UNIQUE(fund_code, report_quarter, industry)
);

CREATE INDEX IF NOT EXISTS idx_fund_ind_quarter ON fund_industry_allocation(fund_code, report_quarter);

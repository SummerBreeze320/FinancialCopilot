-- ==============================================================================
-- 市场基础数据表 (股票/期货/理财/上市企业)
-- ==============================================================================

-- 1. 扩展 stock_info 表 (追加上市日期、总股本)
ALTER TABLE stock_info ADD COLUMN IF NOT EXISTS listing_date DATE;
ALTER TABLE stock_info ADD COLUMN IF NOT EXISTS total_shares NUMERIC(15, 2);
ALTER TABLE stock_info ADD COLUMN IF NOT EXISTS close_price NUMERIC(12, 4);

-- 2. 期货合约信息表
CREATE TABLE IF NOT EXISTS futures_info (
    futures_code VARCHAR(20) PRIMARY KEY,
    futures_name VARCHAR(100) NOT NULL,
    exchange VARCHAR(20) NOT NULL,                        -- SHFE / DCE / CZCE / CFFEX / GFEX
    category VARCHAR(50),                                 -- 商品期货 / 金融期货
    underlying VARCHAR(50),                               -- 标的物 (如: 螺纹钢、铜、沪深300)
    delivery_date DATE,
    listing_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_futures_exchange ON futures_info(exchange);
CREATE INDEX IF NOT EXISTS idx_futures_category ON futures_info(category);

-- 3. 银行理财产品信息表
CREATE TABLE IF NOT EXISTS wealth_product_info (
    product_code VARCHAR(20) PRIMARY KEY,
    product_name VARCHAR(200) NOT NULL,
    bank_name VARCHAR(100),
    product_type VARCHAR(50),                              -- 固定收益类 / 混合类 / 权益类 / 商品及衍生品类
    risk_level VARCHAR(10),                               -- R1(低风险) ~ R5(高风险)
    expected_annual_return NUMERIC(8, 2),                  -- 预期年化收益率 (%)
    min_purchase_amount NUMERIC(12, 2),                   -- 起购金额 (元)
    product_term_days INT,                                -- 产品期限 (天)
    start_date DATE,
    end_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wealth_type ON wealth_product_info(product_type);
CREATE INDEX IF NOT EXISTS idx_wealth_risk ON wealth_product_info(risk_level);

-- 4. 上市企业信息表
CREATE TABLE IF NOT EXISTS enterprise_info (
    enterprise_id VARCHAR(20) PRIMARY KEY,               -- stock_code 作为 ID
    enterprise_name VARCHAR(100) NOT NULL,
    stock_code VARCHAR(20) REFERENCES stock_info(stock_code),
    exchange VARCHAR(20),
    industry VARCHAR(50),
    listing_date DATE,
    total_shares NUMERIC(15, 2),                           -- 总股本 (万股)
    market_cap_billion NUMERIC(12, 2) DEFAULT 0.00,       -- 总市值 (亿元)
    legal_representative VARCHAR(50),
    registered_capital NUMERIC(12, 2),                    -- 注册资本 (万元)
    business_scope TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_enterprise_industry ON enterprise_info(industry);
CREATE INDEX IF NOT EXISTS idx_enterprise_exchange ON enterprise_info(exchange);

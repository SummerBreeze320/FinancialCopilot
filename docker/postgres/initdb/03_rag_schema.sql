-- ==============================================================================
-- 金融指标与板块分类混合 RAG Schema 初始化脚本
-- 引擎: PostgreSQL 16 + PGVector + pg_trgm
-- ==============================================================================

-- 1. 基础扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ==============================================================================
-- 2. 基金指标 RAG 元数据与向量检索表 (metrics.json)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS rag_fund_metric (
    mnemonic VARCHAR(64) PRIMARY KEY,                  -- 指标助记码 (如: f_return_1y, f_risk_maxdownside)
    index_name VARCHAR(128) NOT NULL,                  -- 指标中文名 (如: 近1年回报, 最大回撤)
    parent_name VARCHAR(64) NOT NULL,                  -- 所属大类 (收益指标/风险指标/通用指标等)
    description TEXT,                                  -- 业务释义与口径说明
    embedding_text TEXT NOT NULL,                      -- 向量化原始语料
    embedding vector(1024),                            -- qwen3-embedding 1024 维向量
    source_indicator_id BIGINT,                        -- 原始数据源 ID
    supported_usage VARCHAR(32)[],                     -- 支持用法: ARRAY['filter', 'sort']
    applicable_products VARCHAR(64),                   -- 适用产品类别代码
    aliases TEXT[],                                    -- 别名同义词数组
    version INT DEFAULT 1,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- HNSW 高维向量余弦距离索引
CREATE INDEX IF NOT EXISTS idx_rag_metric_embedding_hnsw 
ON rag_fund_metric USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- 结构化过滤与全文/模糊检索索引
CREATE INDEX IF NOT EXISTS idx_rag_metric_parent ON rag_fund_metric(parent_name);
CREATE INDEX IF NOT EXISTS idx_rag_metric_usage ON rag_fund_metric USING gin(supported_usage);
CREATE INDEX IF NOT EXISTS idx_rag_metric_aliases ON rag_fund_metric USING gin(aliases);
CREATE INDEX IF NOT EXISTS idx_rag_metric_name_trgm ON rag_fund_metric USING gin (index_name gin_trgm_ops);

-- ==============================================================================
-- 3. 基金板块分类 RAG 元数据与向量检索表 (sectors.json)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS rag_fund_sector (
    sector_id VARCHAR(32) PRIMARY KEY,                 -- 板块编码 (source_sector_id)
    parent_id VARCHAR(32),                             -- 上级板块编码 (根节点为 NULL)
    name VARCHAR(128) NOT NULL,                        -- 板块中文名称
    name_en VARCHAR(128),                              -- 英文名称
    aliases TEXT[],                                    -- 别名同义词数组
    description TEXT,                                  -- 板块描述
    embedding_text TEXT NOT NULL,                      -- 向量化原始语料
    embedding vector(1024),                            -- qwen3-embedding 1024 维向量
    is_leaf BOOLEAN DEFAULT TRUE,                      -- 是否为叶子分类节点
    element_type INT DEFAULT 6,                        -- 分类类型
    tree_level INT DEFAULT 0,                          -- 层级深度 (根节点为 0)
    full_path_names TEXT,                              -- 完整分类层级链 (如: '内地公募基金 > 基金市场类 > 开放式基金')
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- HNSW 高维向量索引
CREATE INDEX IF NOT EXISTS idx_rag_sector_embedding_hnsw 
ON rag_fund_sector USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- 树状拓扑与元数据检索索引
CREATE INDEX IF NOT EXISTS idx_rag_sector_parent ON rag_fund_sector(parent_id);
CREATE INDEX IF NOT EXISTS idx_rag_sector_leaf ON rag_fund_sector(is_leaf);
CREATE INDEX IF NOT EXISTS idx_rag_sector_aliases ON rag_fund_sector USING gin(aliases);
CREATE INDEX IF NOT EXISTS idx_rag_sector_name_trgm ON rag_fund_sector USING gin (name gin_trgm_ops);

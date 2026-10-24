"""PostgreSQL pgvector 数据库表结构与索引初始化脚本。"""

import asyncio
import asyncpg
from app.config import settings


INIT_SQL = """
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. 知识库主文档表
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    cognitive_layer VARCHAR(32) NOT NULL,
    category_path VARCHAR(128) NOT NULL,
    institution VARCHAR(128),
    author VARCHAR(128),
    publish_date DATE,
    report_period VARCHAR(32),
    is_latest_period BOOLEAN DEFAULT FALSE,
    authority_score FLOAT DEFAULT 1.0,
    file_path VARCHAR(512),
    file_hash VARCHAR(64) UNIQUE,
    chunk_count INT DEFAULT 0,
    status VARCHAR(32) DEFAULT 'PROCESSING',
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. 知识切片与向量索引表
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    page_number INT,
    section_title VARCHAR(256),
    content TEXT NOT NULL,
    content_length INT NOT NULL,
    embedding vector(1024),
    cognitive_layer VARCHAR(32) NOT NULL,
    category_path VARCHAR(128) NOT NULL,
    stock_codes TEXT[] DEFAULT '{}',
    fund_codes TEXT[] DEFAULT '{}',
    metadata JSONB DEFAULT '{}'::jsonb,
    tsv_content tsvector,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 建立复合索引
CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_category ON knowledge_chunks(category_path);
CREATE INDEX IF NOT EXISTS idx_chunks_stocks ON knowledge_chunks USING GIN(stock_codes);
CREATE INDEX IF NOT EXISTS idx_chunks_funds ON knowledge_chunks USING GIN(fund_codes);
CREATE INDEX IF NOT EXISTS idx_chunks_metadata ON knowledge_chunks USING GIN(metadata);
CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON knowledge_chunks USING GIN(tsv_content);

-- HNSW 余弦相似度向量索引
CREATE INDEX IF NOT EXISTS idx_chunks_vector ON knowledge_chunks 
USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
"""


async def init_database(db_url: str | None = None) -> None:
    """初始化数据库扩展、表结构与索引。"""
    url = db_url or settings.database_url
    print(f"[INIT-DB] 连接数据库: {url}")
    conn = await asyncpg.connect(url)
    try:
        print("[INIT-DB] 正在执行 DDL 建表与索引初始化...")
        await conn.execute(INIT_SQL)
        print("[INIT-DB] 数据库初始化成功！")
    finally:
        await conn.close()


if __name__ == "__main__":
    asyncio.run(init_database())

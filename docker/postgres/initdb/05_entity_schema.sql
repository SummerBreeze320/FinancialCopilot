CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS entity_catalog_version (
    version_id UUID PRIMARY KEY,
    catalog_version TEXT NOT NULL,
    source TEXT NOT NULL,
    source_sha256 TEXT NOT NULL,
    entity_count INTEGER NOT NULL DEFAULT 0 CHECK (entity_count >= 0),
    status TEXT NOT NULL CHECK (status IN ('building', 'active', 'failed', 'retired')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_entity_catalog_single_active
ON entity_catalog_version ((status)) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_entity_catalog_source_sha256
ON entity_catalog_version(source_sha256);

CREATE TABLE IF NOT EXISTS entity_catalog_item (
    version_id UUID NOT NULL REFERENCES entity_catalog_version(version_id) ON DELETE CASCADE,
    entity_id TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN (
        'fund_code', 'fund_name', 'exchange_fund_name', 'company_name',
        'manager_name', 'track_index_name', 'track_index_code',
        'stock_code', 'stock_name', 'bond_code', 'bond_name'
    )),
    canonical_value TEXT NOT NULL,
    normalized_value TEXT NOT NULL,
    aliases JSONB NOT NULL,
    normalized_aliases JSONB NOT NULL,
    metadata JSONB NOT NULL,
    search_text TEXT NOT NULL,
    embedding VECTOR(1024) NOT NULL,
    PRIMARY KEY (version_id, entity_id)
);

CREATE INDEX IF NOT EXISTS idx_entity_item_version_category
ON entity_catalog_item(version_id, category);
CREATE INDEX IF NOT EXISTS idx_entity_item_normalized_value
ON entity_catalog_item(version_id, normalized_value);
CREATE INDEX IF NOT EXISTS idx_entity_item_aliases_gin
ON entity_catalog_item USING GIN(normalized_aliases);
CREATE INDEX IF NOT EXISTS idx_entity_item_embedding_hnsw
ON entity_catalog_item USING hnsw (embedding vector_cosine_ops);

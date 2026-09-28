-- ==============================================================================
-- 迁移: 为 rag_fund_metric 增加有序参数契约元数据列与校验约束
-- 日期: 2026-09-29
-- 特性: 幂等执行 (可在已存在的 volume / 表结构上重复执行)
-- ==============================================================================

-- 1. 添加参数契约元数据列
ALTER TABLE rag_fund_metric ADD COLUMN IF NOT EXISTS parameters JSONB NOT NULL DEFAULT '[]';
ALTER TABLE rag_fund_metric ADD COLUMN IF NOT EXISTS result_contract JSONB NOT NULL DEFAULT '{}';
ALTER TABLE rag_fund_metric ADD COLUMN IF NOT EXISTS parameter_schema_version INT NOT NULL DEFAULT 1;
ALTER TABLE rag_fund_metric ADD COLUMN IF NOT EXISTS contract_status VARCHAR(32) NOT NULL DEFAULT 'NEEDS_REVIEW';
ALTER TABLE rag_fund_metric ADD COLUMN IF NOT EXISTS contract_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 2. 幂等添加 CHECK 约束 (通过 pg_constraint 条件判断)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_rag_metric_parameters_array'
    ) THEN
        ALTER TABLE rag_fund_metric
            ADD CONSTRAINT chk_rag_metric_parameters_array
            CHECK (jsonb_typeof(parameters) = 'array');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_rag_metric_result_contract_object'
    ) THEN
        ALTER TABLE rag_fund_metric
            ADD CONSTRAINT chk_rag_metric_result_contract_object
            CHECK (jsonb_typeof(result_contract) = 'object');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_rag_metric_contract_status'
    ) THEN
        ALTER TABLE rag_fund_metric
            ADD CONSTRAINT chk_rag_metric_contract_status
            CHECK (contract_status IN ('INFERRED', 'REVIEWED', 'NEEDS_REVIEW'));
    END IF;
END $$;

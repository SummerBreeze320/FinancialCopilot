-- ==============================================================================
-- 金融研究 Agent (FinancialCopilot) 集群化执行与租约流转持久化脚本
-- ==============================================================================

CREATE TABLE IF NOT EXISTS research_run_state (
    run_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    graph_snapshot JSONB NOT NULL DEFAULT '{}',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_research_run_state_user ON research_run_state (user_id, run_id);
CREATE INDEX IF NOT EXISTS idx_research_run_state_conv ON research_run_state (conversation_id);

CREATE TABLE IF NOT EXISTS research_node_attempt (
    run_id UUID NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    worker_id VARCHAR(128),
    lease_until TIMESTAMPTZ,
    artifact JSONB,
    PRIMARY KEY (run_id, node_id, attempt)
);

CREATE INDEX IF NOT EXISTS idx_research_node_attempt_claim ON research_node_attempt (status, lease_until);

CREATE TABLE IF NOT EXISTS research_run_event (
    run_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_research_run_event_replay ON research_run_event (user_id, run_id, sequence_no);

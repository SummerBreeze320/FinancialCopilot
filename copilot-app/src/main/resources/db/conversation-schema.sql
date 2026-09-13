-- Durable conversation history schema (source of truth for user-visible research conversations)

CREATE TABLE IF NOT EXISTS research_conversation (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    last_message_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS idx_conversation_user_last_message
    ON research_conversation(user_id, last_message_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS conversation_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES research_conversation(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    content TEXT NOT NULL DEFAULT '',
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(500) NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    UNIQUE(conversation_id, sequence_no),
    UNIQUE(user_id, run_id, role)
);

CREATE INDEX IF NOT EXISTS idx_message_conversation_sequence
    ON conversation_message(conversation_id, sequence_no DESC);

CREATE INDEX IF NOT EXISTS idx_message_user_run
    ON conversation_message(user_id, run_id);

CREATE TABLE IF NOT EXISTS agent_tool_audit (
    id BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES research_conversation(id) ON DELETE CASCADE,
    assistant_message_id BIGINT NOT NULL REFERENCES conversation_message(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    tool_call_id VARCHAR(200) NOT NULL,
    tool_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    arguments JSONB NOT NULL DEFAULT '{}',
    result_summary VARCHAR(1000) NULL,
    result_hash VARCHAR(128) NULL,
    artifact_ids JSONB NOT NULL DEFAULT '[]',
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    duration_ms BIGINT NULL,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(500) NULL,
    UNIQUE(user_id, run_id, tool_call_id)
);

CREATE INDEX IF NOT EXISTS idx_audit_user_run
    ON agent_tool_audit(user_id, run_id, id DESC);

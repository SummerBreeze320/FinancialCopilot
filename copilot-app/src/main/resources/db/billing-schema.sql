-- A provider trade can credit only one order, including under concurrent callbacks.
CREATE UNIQUE INDEX IF NOT EXISTS idx_recharge_paid_trade
    ON sys_recharge_order (pay_channel, third_party_trade_no)
    WHERE order_status = 'PAID' AND third_party_trade_no IS NOT NULL;

-- 投研任务运行算力预占与结算表
CREATE TABLE IF NOT EXISTS sys_point_reservation (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    workflow_run_id VARCHAR(64) NOT NULL UNIQUE,
    estimated_points BIGINT NOT NULL,
    actual_points BIGINT DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_point_reservation_user ON sys_point_reservation(user_id);


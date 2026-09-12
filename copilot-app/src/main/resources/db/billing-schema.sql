-- A provider trade can credit only one order, including under concurrent callbacks.
CREATE UNIQUE INDEX IF NOT EXISTS idx_recharge_paid_trade
    ON sys_recharge_order (pay_channel, third_party_trade_no)
    WHERE order_status = 'PAID' AND third_party_trade_no IS NOT NULL;

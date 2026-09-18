import pytest
from memory_service.domain.enums import MemoryType
from memory_service.engine.compilation.memory_compiler import MemoryCompiler


def test_compiler_fact():
    compiler = MemoryCompiler()
    pack = compiler.compile(
        memory_id="mem-1",
        version=1,
        memory_type=MemoryType.FACT,
        content="User risk level is 稳健型",
        data_json={"subject": "user", "predicate": "risk_tolerance", "object_value": "稳健型"},
    )
    assert "[FACT] user.risk_tolerance=稳健型" in pack.compact_text
    assert pack.token_count > 0
    assert "user" in pack.retrieval_text


def test_compiler_procedure_preserves_safety():
    compiler = MemoryCompiler()
    proc_data = {
        "task_type": "fund_rebalance",
        "trigger_conditions": {"task_type": "fund_rebalance"},
        "preconditions": ["Check user KYC and risk match", "Within trading hours"],
        "steps": [
            {"action": "validate_risk", "description": "Validate user profile"},
            {"action": "execute_swap", "description": "Execute rebalance orders"},
        ],
        "applicability": {"market": "CN_A"},
        "failure_signatures": [{"error_type": "MARKET_CLOSED"}],
        "recovery_actions": [{"on_error": "MARKET_CLOSED", "action": "Queue for next trading day"}],
    }
    pack = compiler.compile(
        memory_id="mem-proc-1",
        version=1,
        memory_type=MemoryType.PROCEDURE,
        content="Procedure for fund rebalance",
        data_json=proc_data,
    )
    # 严格检验：compact_text 中必须保留 pre 条件与 recovery
    assert "Check user KYC and risk match" in pack.compact_text
    assert "Within trading hours" in pack.compact_text
    assert "MARKET_CLOSED" in pack.compact_text
    assert "Queue for next trading day" in pack.compact_text
    assert pack.token_count > 0

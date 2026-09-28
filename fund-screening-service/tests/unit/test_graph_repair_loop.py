import pytest
from app.graph.repair import clarification_node, decide_validation_outcome, mechanical_repair_node
from app.graph.state import empty_state


def test_decide_outcome_valid_leads_to_finalize() -> None:
    state = empty_state("成立大于5年")
    state["draft_dsl"] = {"version": "fund-screening/v1", "operator": "AND", "children": [], "limit": 20}
    state["validation_errors"] = []
    assert decide_validation_outcome(state) == "finalize"


def test_decide_outcome_repairable_within_limit_leads_to_repair() -> None:
    state = empty_state("回撤小于10%")
    state["draft_dsl"] = {"version": "fund-screening/v1"}
    state["validation_errors"] = [{"code": "NEGATIVE_DRAWDOWN_STORAGE", "message": "修复负值回撤"}]
    state["repair_attempts"] = 0
    assert decide_validation_outcome(state) == "repair"


def test_decide_outcome_exhausted_repairs_leads_to_clarify() -> None:
    state = empty_state("测试连续坏输入")
    state["draft_dsl"] = {"version": "fund-screening/v1"}
    state["validation_errors"] = [{"code": "LIMIT_TOO_LARGE", "message": "数量超标"}]
    state["repair_attempts"] = 2  # 已达上限 2
    assert decide_validation_outcome(state) == "clarify"


@pytest.mark.asyncio
async def test_mechanical_repair_applies_negative_drawdown_repair() -> None:
    state = empty_state("回撤小于15%")
    state["draft_dsl"] = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [{
            "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000", "match": "SELF_OR_DESCENDANT"}]},
            "metric": {"operator": "AND", "children": [{"metric": "f_risk_maxdownside", "operator": "<", "value": 15, "unit": "PERCENT"}]},
        }],
        "limit": 20,
    }
    state["repair_attempts"] = 0
    update = await mechanical_repair_node(state)
    assert update["repair_attempts"] == 1
    child = update["draft_dsl"]["children"][0]["metric"]["children"][0]
    assert child["operator"] == ">="
    assert child["value"] == -15


@pytest.mark.asyncio
async def test_clarification_node_produces_structured_payload() -> None:
    state = empty_state("坏输入")
    state["validation_errors"] = [{"code": "LIMIT_TOO_LARGE", "message": "筛选数量超过服务上限"}]
    update = await clarification_node(state)
    assert update["clarification"]["code"] == "LIMIT_TOO_LARGE"
    assert update["final_dsl"] is None
    assert update["trace"][-1]["stage"] == "clarification"

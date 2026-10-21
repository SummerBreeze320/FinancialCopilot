from app.graph.state import FundScreeningState, empty_state


def test_empty_state_initializes_bounded_repair_workflow() -> None:
    state = empty_state("筛选低回撤基金", conversation_id="conv-123")
    assert state["query"] == "筛选低回撤基金"
    assert state["conversation_id"] == "conv-123"
    assert state["repair_attempts"] == 0
    assert state["candidate_pool"] == []
    assert state["candidate_contract_index"] == {}
    assert state["validation_errors"] == []
    assert state["repairs"] == []
    assert state["trace"] == []
    assert state["intent"] == "CREATE"
    assert state["edit_complexity"] == "NONE"
    assert state["route"] == "full"
    assert state["draft_dsl"] is None
    assert state["final_dsl"] is None
    assert state["clarification"] is None


def test_state_accepts_previous_dsl_for_editing() -> None:
    prev = {"version": "fund-screening/v1", "operator": "AND", "children": [], "limit": 20}
    state = empty_state("改成前50只", previous_dsl=prev)
    assert state["previous_dsl"] == prev
    assert state["intent"] == "CREATE"

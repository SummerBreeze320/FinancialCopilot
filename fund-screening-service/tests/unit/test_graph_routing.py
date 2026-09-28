from app.graph.router import classify_screening_route
from app.graph.state import empty_state


def test_classify_route_direct_for_exact_match_candidate() -> None:
    state = empty_state("招商中证白酒指数")
    state["candidate_pool"] = [{"domain": "entity", "key": "161725.OF", "score": 1.0}]
    assert classify_screening_route(state) == "direct"


def test_classify_route_simple_for_single_group_and_conditions() -> None:
    state = empty_state("规模大于10亿且成立超过5年")
    assert classify_screening_route(state) == "simple"


def test_classify_route_full_for_or_not_complex_queries() -> None:
    state1 = empty_state("规模大于10亿或者最大回撤小于10%")
    assert classify_screening_route(state1) == "full"

    state2 = empty_state("排除QDII基金")
    assert classify_screening_route(state2) == "full"

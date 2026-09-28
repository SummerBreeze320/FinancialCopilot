from app.pipeline.router import Route, RoutingSignals, classify_route


def test_route_is_full_for_cross_group_or() -> None:
    assert classify_route(RoutingSignals(group_count=2, has_or=True, has_not=False, confidence=0.99)) is Route.FULL


def test_route_is_direct_for_one_high_confidence_complete_condition() -> None:
    assert classify_route(RoutingSignals(group_count=1, has_or=False, has_not=False, confidence=0.97, candidate_complete=True)) is Route.DIRECT


def test_route_is_simple_for_one_and_only_incomplete_condition() -> None:
    assert classify_route(RoutingSignals(group_count=1, has_or=False, has_not=False, confidence=0.72, candidate_complete=False)) is Route.SIMPLE

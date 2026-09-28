from app.pipeline.router import Route, classify_route


def test_route_is_full_for_cross_group_or() -> None:
    assert classify_route(group_count=2, has_or=True, has_not=False, confidence=0.99) is Route.FULL

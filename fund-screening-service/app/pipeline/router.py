from enum import StrEnum


class Route(StrEnum):
    DIRECT = "direct"
    SIMPLE = "simple"
    FULL = "full"


def classify_route(group_count: int, has_or: bool, has_not: bool, confidence: float) -> Route:
    if group_count > 1 or has_or or has_not:
        return Route.FULL
    return Route.DIRECT if confidence >= 0.95 else Route.SIMPLE

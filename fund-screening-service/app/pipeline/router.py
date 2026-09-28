from enum import StrEnum
from dataclasses import dataclass


class Route(StrEnum):
    DIRECT = "direct"
    SIMPLE = "simple"
    FULL = "full"


@dataclass(frozen=True)
class RoutingSignals:
    """路由只依赖可审计的语义复杂度与候选置信度。"""

    group_count: int
    has_or: bool
    has_not: bool
    confidence: float
    candidate_complete: bool = False


def classify_route(signals: RoutingSignals) -> Route:
    if signals.group_count > 1 or signals.has_or or signals.has_not:
        return Route.FULL
    if signals.candidate_complete and signals.confidence >= 0.95:
        return Route.DIRECT
    return Route.SIMPLE

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class CandidateContract:
    domain: str
    key: str
    name: str
    aliases: tuple[str, ...] = ()
    supported_operators: tuple[str, ...] = (">", ">=", "<", "<=", "=", "!=")
    value_type: str = "NUMBER"
    supported_usage: tuple[str, ...] = ("filter",)
    binding_role: str | None = None
    preferred_metric: str | None = None
    score: float = 1.0
    parameters: tuple[Any, ...] = ()

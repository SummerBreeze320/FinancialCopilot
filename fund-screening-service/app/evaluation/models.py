from dataclasses import dataclass


@dataclass(frozen=True)
class EvaluationCase:
    expected_key: str
    recalled_keys: list[str]
    error_reason: str | None = None

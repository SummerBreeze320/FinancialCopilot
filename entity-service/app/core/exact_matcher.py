from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

from app.catalog.models import CatalogRecord
from app.core.normalization import NormalizedText
from app.models.api import Span


@dataclass(frozen=True, slots=True)
class RawCandidate:
    mention: str
    span: Span
    entity: CatalogRecord
    normalized_alias: str
    exact_canonical: bool


@dataclass(slots=True)
class _Node:
    children: dict[str, int] = field(default_factory=dict)
    fail: int = 0
    outputs: list[tuple[str, tuple[CatalogRecord, ...]]] = field(default_factory=list)


class ExactMatcher:
    def __init__(self, nodes: list[_Node]) -> None:
        self._nodes = nodes

    @classmethod
    def build(cls, records: tuple[CatalogRecord, ...] | list[CatalogRecord]) -> "ExactMatcher":
        alias_map: dict[str, list[CatalogRecord]] = {}
        for record in records:
            for alias in record.normalized_aliases:
                if alias:
                    alias_map.setdefault(alias, []).append(record)

        nodes = [_Node()]
        for alias, entities in alias_map.items():
            state = 0
            for char in alias:
                next_state = nodes[state].children.get(char)
                if next_state is None:
                    next_state = len(nodes)
                    nodes[state].children[char] = next_state
                    nodes.append(_Node())
                state = next_state
            nodes[state].outputs.append((alias, tuple(entities)))

        queue: deque[int] = deque()
        for child in nodes[0].children.values():
            queue.append(child)
        while queue:
            state = queue.popleft()
            for char, child in nodes[state].children.items():
                queue.append(child)
                fallback = nodes[state].fail
                while fallback and char not in nodes[fallback].children:
                    fallback = nodes[fallback].fail
                nodes[child].fail = nodes[fallback].children.get(char, 0)
                nodes[child].outputs.extend(nodes[nodes[child].fail].outputs)
        return cls(nodes)

    def find(self, normalized: NormalizedText) -> list[RawCandidate]:
        state = 0
        hits: list[RawCandidate] = []
        seen: set[tuple[int, int, str]] = set()
        for end_index, char in enumerate(normalized.text):
            while state and char not in self._nodes[state].children:
                state = self._nodes[state].fail
            state = self._nodes[state].children.get(char, 0)
            for alias, entities in self._nodes[state].outputs:
                start_index = end_index - len(alias) + 1
                end_exclusive = end_index + 1
                if not self._valid_boundary(normalized.text, start_index, end_exclusive, alias):
                    continue
                span = normalized.original_span(start_index, end_exclusive)
                mention = normalized.source[span.start:span.end]
                for entity in entities:
                    key = (span.start, span.end, entity.id)
                    if key in seen:
                        continue
                    seen.add(key)
                    hits.append(RawCandidate(
                        mention=mention,
                        span=span,
                        entity=entity,
                        normalized_alias=alias,
                        exact_canonical=alias == entity.normalized_value,
                    ))
        return sorted(hits, key=lambda hit: (hit.span.start, -(hit.span.end - hit.span.start), hit.entity.id))

    @staticmethod
    def _valid_boundary(text: str, start: int, end: int, alias: str) -> bool:
        if not any(char.isdigit() for char in alias):
            return True
        if start > 0 and text[start - 1].isascii() and text[start - 1].isalnum():
            return False
        if end < len(text) and text[end].isascii() and text[end].isalnum():
            return False
        return True

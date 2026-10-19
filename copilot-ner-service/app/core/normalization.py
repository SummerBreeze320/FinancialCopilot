from __future__ import annotations

from dataclasses import dataclass
import unicodedata

from app.models.api import Span


@dataclass(frozen=True, slots=True)
class NormalizedText:
    source: str
    text: str
    source_ranges: tuple[tuple[int, int], ...]

    def original_span(self, start: int, end: int) -> Span:
        if start < 0 or end <= start or end > len(self.source_ranges):
            raise ValueError("invalid normalized span")
        return Span(start=self.source_ranges[start][0], end=self.source_ranges[end - 1][1])


def normalize_with_offsets(source: str) -> NormalizedText:
    output: list[str] = []
    ranges: list[tuple[int, int]] = []
    pending_space: tuple[int, int] | None = None

    for index, char in enumerate(source):
        normalized = unicodedata.normalize("NFKC", char).casefold()
        for normalized_char in normalized:
            if normalized_char.isspace():
                if output and pending_space is None:
                    pending_space = (index, index + 1)
                elif pending_space is not None:
                    pending_space = (pending_space[0], index + 1)
                continue
            if pending_space is not None:
                output.append(" ")
                ranges.append(pending_space)
                pending_space = None
            output.append(normalized_char)
            ranges.append((index, index + 1))

    return NormalizedText(source=source, text="".join(output), source_ranges=tuple(ranges))


def normalize_alias(value: str) -> str:
    return normalize_with_offsets(value.strip()).text

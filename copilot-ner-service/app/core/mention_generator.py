from __future__ import annotations

from dataclasses import dataclass
import re

from app.core.normalization import NormalizedText
from app.models.api import Span


TOKEN_PATTERN = re.compile(r"[a-z0-9*][a-z0-9.*_-]{1,23}|[\u3400-\u9fff]{2,24}", re.IGNORECASE)
LEADING_STOPWORDS = ("帮我比较", "比较", "请帮我", "请分析", "分析", "请看", "看看", "查询", "查一下")
TRAILING_STOPWORDS = ("怎么样", "如何", "走势", "表现", "和", "与", "及")
STOPWORDS = {"最近表现", "最近走势", "怎么样", "如何", "比较", "分析", "查询", "请问"}


@dataclass(frozen=True, slots=True)
class GeneratedMention:
    mention: str
    span: Span
    normalized_text: str


class MentionGenerator:
    def __init__(self, max_spans: int = 128) -> None:
        self.max_spans = max_spans

    def generate(self, normalized: NormalizedText, covered_spans: list[Span]) -> list[GeneratedMention]:
        output: list[GeneratedMention] = []
        seen: set[tuple[int, int]] = set()
        for match in TOKEN_PATTERN.finditer(normalized.text):
            start, end = self._trim(normalized.text, match.start(), match.end())
            if end - start < 2:
                continue
            candidates = [(start, end)]
            if all("\u3400" <= char <= "\u9fff" for char in normalized.text[start:end]):
                candidates.extend(self._windows(start, end))
            for candidate_start, candidate_end in candidates:
                if len(output) >= self.max_spans:
                    return output
                if (candidate_start, candidate_end) in seen:
                    continue
                span = normalized.original_span(candidate_start, candidate_end)
                if any(span.start >= covered.start and span.end <= covered.end for covered in covered_spans):
                    continue
                value = normalized.text[candidate_start:candidate_end]
                if value in STOPWORDS:
                    continue
                seen.add((candidate_start, candidate_end))
                output.append(GeneratedMention(
                    mention=normalized.source[span.start:span.end],
                    span=span,
                    normalized_text=value,
                ))
        return output

    @staticmethod
    def _trim(text: str, start: int, end: int) -> tuple[int, int]:
        changed = True
        while changed:
            changed = False
            value = text[start:end]
            for prefix in LEADING_STOPWORDS:
                if value.startswith(prefix) and len(value) > len(prefix) + 1:
                    start += len(prefix)
                    changed = True
                    break
            value = text[start:end]
            for suffix in TRAILING_STOPWORDS:
                if value.endswith(suffix) and len(value) > len(suffix) + 1:
                    end -= len(suffix)
                    changed = True
                    break
        return start, end

    @staticmethod
    def _windows(start: int, end: int) -> list[tuple[int, int]]:
        length = end - start
        windows: list[tuple[int, int]] = []
        for size in range(min(length - 1, 12), 1, -1):
            for offset in range(start, end - size + 1):
                windows.append((offset, offset + size))
        return windows

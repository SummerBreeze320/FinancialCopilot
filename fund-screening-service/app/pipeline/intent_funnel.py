from __future__ import annotations

import inspect
import re
from collections.abc import Awaitable, Callable
from typing import Any

from app.models.intent import IntentDecision, IntentType
from app.pipeline.editor import EditKind, classify_edit

IntentClassifier = Callable[[str, dict[str, Any], dict[str, Any] | None], IntentDecision | Awaitable[IntentDecision]]


class IntentFunnel:
    """以规则优先、同模型分层兜底的方式识别基金筛选意图。"""

    IntentType = IntentType

    _CONTEXTUAL_PATTERNS = (
        r"^(?:再|更|少|多|严格|宽松).*(?:一点|一些|些)?$",
        r"(?:刚才|之前|上一轮|上轮|在此基础上|这些条件|那个|刚刚)",
        r"条件.*(?:一点|一些|些)",
    )

    def __init__(
        self,
        context_classifier: IntentClassifier | None = None,
        complex_classifier: IntentClassifier | None = None,
    ) -> None:
        self._context_classifier = context_classifier
        self._complex_classifier = complex_classifier

    async def classify(
        self,
        query: str,
        previous_dsl: dict[str, Any] | None,
        clarification: dict[str, Any] | None = None,
    ) -> IntentDecision:
        """按规则、上下文模型、复杂模型顺序返回一级意图。"""
        rule = self._classify_rule(query, previous_dsl, clarification)
        if rule is not None:
            return rule

        context_decision = await self._ask(self._context_classifier, query, previous_dsl, clarification)
        if self._is_reliable(context_decision):
            return context_decision

        complex_decision = await self._ask(self._complex_classifier, query, previous_dsl, clarification)
        if self._is_reliable(complex_decision):
            return complex_decision

        return IntentDecision(
            intent="CREATE",
            confidence=1.0,
            evidence=[],
            source="RULE",
            reason_codes=["AMBIGUOUS_CONTEXT_DEFAULT_CREATE"],
        )

    def classify_intent(
        self,
        query: str,
        previous_dsl: dict[str, Any] | None = None,
        clarification: dict[str, Any] | None = None,
    ) -> IntentDecision:
        rule = self._classify_rule(query, previous_dsl, clarification)
        if rule is not None:
            return rule
        return IntentDecision(
            intent="CREATE",
            confidence=1.0,
            evidence=[],
            source="RULE",
            reason_codes=["DEFAULT_CREATE"],
        )

    def _classify_rule(
        self,
        query: str,
        previous_dsl: dict[str, Any] | None,
        clarification: dict[str, Any] | None,
    ) -> IntentDecision | None:
        if previous_dsl is None:
            return IntentDecision(
                intent="CREATE", confidence=1.0, evidence=[], source="RULE", reason_codes=["NO_PREVIOUS_DSL"]
            )

        if clarification and query.strip():
            return IntentDecision(
                intent="EDIT", confidence=1.0, evidence=[query.strip()], source="RULE",
                reason_codes=["RESUME_PENDING_CLARIFICATION"],
            )

        edit_kind = classify_edit(query, previous_dsl)
        if edit_kind is EditKind.SIMPLE:
            return IntentDecision(
                intent="EDIT", confidence=1.0, evidence=[query.strip()], source="RULE",
                reason_codes=["EXPLICIT_SIMPLE_EDIT"],
            )
        if edit_kind is EditKind.COMPLEX and self._has_explicit_edit(query):
            return IntentDecision(
                intent="EDIT", confidence=1.0, evidence=[query.strip()], source="RULE",
                reason_codes=["EXPLICIT_COMPLEX_EDIT"],
            )
        if self._is_contextual(query):
            return None
        return IntentDecision(
            intent="CREATE", confidence=1.0, evidence=[], source="RULE",
            reason_codes=["NO_EXPLICIT_EDIT_REFERENCE", "DEFAULT_CREATE"],
        )

    @staticmethod
    def _has_explicit_edit(query: str) -> bool:
        return any(token in query for token in ("新增", "增加", "加一个", "再加", "补充", "删除", "去掉", "移除", "不要", "替换", "换成", "重新筛选", "重新来", "清空"))

    def _is_contextual(self, query: str) -> bool:
        return any(re.search(pattern, query.strip()) for pattern in self._CONTEXTUAL_PATTERNS)

    async def _ask(
        self,
        classifier: IntentClassifier | None,
        query: str,
        previous_dsl: dict[str, Any] | None,
        clarification: dict[str, Any] | None,
    ) -> IntentDecision | None:
        if classifier is None or previous_dsl is None:
            return None
        result = classifier(query, previous_dsl, clarification)
        decision = await result if inspect.isawaitable(result) else result
        return decision

    @staticmethod
    def _is_reliable(decision: IntentDecision | None) -> bool:
        if decision is None or decision.confidence < 0.85 or not decision.evidence:
            return False
        edit_prob = decision.edit_probability
        if decision.create_probability is None or edit_prob is None:
            return True
        return abs(decision.create_probability - edit_prob) >= 0.15

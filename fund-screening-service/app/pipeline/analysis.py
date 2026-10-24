from __future__ import annotations

from dataclasses import dataclass
import inspect
from typing import Any

from app.models.candidate import CandidateContract


@dataclass(frozen=True)
class ConditionPlan:
    """pre-recall plan 中一个可独立召回的条件片段。"""

    condition_id: str
    mention: str
    domain: str


@dataclass(frozen=True)
class RecallPlan:
    domains: tuple[str, ...]
    conditions: tuple[ConditionPlan, ...] = ()


@dataclass(frozen=True)
class AnalysisResult:
    """三层语义处理的可观测输出：计划、候选池与受限裁决。"""

    plan: RecallPlan
    candidate_pool: "CandidatePool"
    decision: dict


class CandidatePool:
    def __init__(self, candidates: list[CandidateContract]) -> None:
        self.candidates = candidates
        self._index = {candidate.key: candidate for candidate in candidates}

    def contract(self, key: str) -> CandidateContract:
        return self._index[key]


class AnalysisService:
    def __init__(self, plan_generator, metric_retriever, scope_retriever, entity_retriever, adjudicator=None) -> None:
        self._plan_generator = plan_generator
        self._retrievers = {"metric": metric_retriever, "scope": scope_retriever, "entity": entity_retriever}
        self._adjudicator = adjudicator

    async def analyze_and_recall(self, query: str) -> CandidatePool:
        plan = await self._make_plan(query)
        return await self._recall(query, plan)

    async def analyze_recall_and_decide(self, query: str) -> AnalysisResult:
        """先生成 plan、再召回、最后只在候选池内裁决并填充参数。"""
        if self._adjudicator is None:
            raise RuntimeError("adjudicator is required for three-layer semantic processing")
        plan = await self._make_plan(query)
        pool = await self._recall(query, plan)
        decision = await self._adjudicator(query, plan, pool)
        return AnalysisResult(plan, pool, decision)

    async def _make_plan(self, query: str) -> RecallPlan:
        plan = self._plan_generator(query)
        return await plan if inspect.isawaitable(plan) else plan

    async def _recall(self, query: str, plan: RecallPlan) -> CandidatePool:
        results = []
        if plan.conditions:
            for condition in plan.conditions:
                results.extend(await self._retrievers[condition.domain].search(condition.mention, 10))
        else:
            for domain in plan.domains:
                results.extend(await self._retrievers[domain].search(query, 10))
        return CandidatePool(list({(item.domain, item.key): item for item in results}.values()))


class RecallByConditionService:
    """依据 SemanticPlan 中每个 condition 的领域分发召回，并生成 byCondition 约束与 contractIndex。"""

    def __init__(self, metric_retriever: Any, scope_retriever: Any, entity_retriever: Any) -> None:
        self._retrievers = {
            "metric": metric_retriever,
            "scope": scope_retriever,
            "entity": entity_retriever,
        }

    async def recall(
        self, plan: Any, target_condition_ids: list[str] | None = None
    ) -> tuple[dict[str, Any], dict[str, CandidateContract]]:
        by_condition: dict[str, Any] = {}
        contract_index: dict[str, CandidateContract] = {}

        # 辅助递归提取叶子
        def extract_scope_leaves(tree: Any) -> list[Any]:
            leaves = []
            for child in getattr(tree, "children", []):
                if hasattr(child, "domain") and getattr(child, "domain") == "scope":
                    leaves.append(child)
                elif hasattr(child, "children"):
                    leaves.extend(extract_scope_leaves(child))
            return leaves

        def extract_metric_leaves(tree: Any) -> list[Any]:
            leaves = []
            for child in getattr(tree, "children", []):
                if hasattr(child, "domain") and getattr(child, "domain") in ("metric", "entity"):
                    leaves.append(child)
                elif hasattr(child, "children"):
                    leaves.extend(extract_metric_leaves(child))
            return leaves

        all_leaves = []
        for group in getattr(plan, "groups", []):
            if hasattr(group, "scope_tree"):
                all_leaves.extend(extract_scope_leaves(group.scope_tree))
            if hasattr(group, "metric_tree"):
                all_leaves.extend(extract_metric_leaves(group.metric_tree))

        if target_condition_ids is not None:
            target_set = set(target_condition_ids)
            all_leaves = [
                leaf for leaf in all_leaves
                if (getattr(leaf, "condition_id", None) or getattr(leaf, "conditionId", None)) in target_set
            ]

        for leaf in all_leaves:
            cid = getattr(leaf, "condition_id", None) or getattr(leaf, "conditionId", None)
            mention = getattr(leaf, "mention", "")
            domain = getattr(leaf, "domain", "metric")

            retriever = self._retrievers.get(domain) or self._retrievers["metric"]
            raw_candidates = await retriever.search(mention, 10)
            from app.retrieval.candidate_ranker import rank_candidates
            candidates = rank_candidates(mention, raw_candidates)

            by_condition[cid] = {
                "mention": mention,
                "domain": domain,
                "candidates": candidates,
                "candidate_details": [
                    {
                        "key": c.key,
                        "name": c.name,
                        "raw_score": c.score,
                        "rank": idx + 1,
                        "default_priority": getattr(c, "default_priority", 0),
                    }
                    for idx, c in enumerate(candidates)
                ],
            }
            for c in candidates:
                contract_index[c.key] = c

        return by_condition, contract_index

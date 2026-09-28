from dataclasses import dataclass

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
        plan = self._plan_generator(query)
        return await self._recall(query, plan)

    async def analyze_recall_and_decide(self, query: str) -> AnalysisResult:
        """先生成 plan、再召回、最后只在候选池内裁决并填充参数。"""
        if self._adjudicator is None:
            raise RuntimeError("adjudicator is required for three-layer semantic processing")
        plan = self._plan_generator(query)
        pool = await self._recall(query, plan)
        decision = await self._adjudicator(query, plan, pool)
        return AnalysisResult(plan, pool, decision)

    async def _recall(self, query: str, plan: RecallPlan) -> CandidatePool:
        results = []
        for domain in plan.domains:
            results.extend(await self._retrievers[domain].search(query, 10))
        return CandidatePool(list({(item.domain, item.key): item for item in results}.values()))

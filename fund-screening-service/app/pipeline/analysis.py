from dataclasses import dataclass

from app.models.candidate import CandidateContract


@dataclass(frozen=True)
class RecallPlan:
    domains: tuple[str, ...]


class CandidatePool:
    def __init__(self, candidates: list[CandidateContract]) -> None:
        self.candidates = candidates
        self._index = {candidate.key: candidate for candidate in candidates}

    def contract(self, key: str) -> CandidateContract:
        return self._index[key]


class AnalysisService:
    def __init__(self, plan_generator, metric_retriever, scope_retriever, entity_retriever) -> None:
        self._plan_generator = plan_generator
        self._retrievers = {"metric": metric_retriever, "scope": scope_retriever, "entity": entity_retriever}

    async def analyze_and_recall(self, query: str) -> CandidatePool:
        plan = self._plan_generator(query)
        results = []
        for domain in plan.domains:
            results.extend(await self._retrievers[domain].search(query, 10))
        return CandidatePool(list({(item.domain, item.key): item for item in results}.values()))

from __future__ import annotations

import json

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.core.resolver import CandidateFusion, ScoredCandidate


class RankedItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    entity_id: str
    relevance: float = Field(ge=0, le=1)
    reason: str


class RankedResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    ranked_candidates: list[RankedItem]


class Reranker:
    def __init__(self, http: httpx.AsyncClient, model: str, api_key: str) -> None:
        self._http = http
        self._model = model
        self._api_key = api_key

    async def rerank(
        self,
        mention: str,
        context: str,
        candidates: list[ScoredCandidate],
    ) -> tuple[list[ScoredCandidate], bool]:
        if len(candidates) < 2:
            return candidates, False
        allowed = {candidate.entity.id: candidate for candidate in candidates}
        candidate_payload = [{
            "entity_id": candidate.entity.id,
            "category": candidate.entity.category.value,
            "canonical_value": candidate.entity.canonical_value,
            "metadata": candidate.entity.metadata,
            "base_confidence": candidate.confidence,
        } for candidate in candidates]
        try:
            response = await self._http.post(
                "/chat/completions",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={
                    "model": self._model,
                    "response_format": {"type": "json_object"},
                    "messages": [
                        {"role": "system", "content": (
                            "你是金融实体消歧器。只能返回候选列表中已有的 entity_id，"
                            "按上下文相关性排序，不得创造实体。输出 JSON: "
                            '{"ranked_candidates":[{"entity_id":"...","relevance":0.0,"reason":"..."}]}'
                        )},
                        {"role": "user", "content": json.dumps({
                            "mention": mention,
                            "context": context,
                            "candidates": candidate_payload,
                        }, ensure_ascii=False)},
                    ],
                },
            )
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
            parsed = RankedResponse.model_validate_json(content)
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError, ValidationError):
            return candidates, True

        result: list[ScoredCandidate] = []
        used: set[str] = set()
        for item in parsed.ranked_candidates:
            if item.entity_id not in allowed or item.entity_id in used:
                continue
            used.add(item.entity_id)
            result.append(CandidateFusion.add_context(allowed[item.entity_id], item.relevance))
        result.extend(candidate for candidate in candidates if candidate.entity.id not in used)
        return result, False

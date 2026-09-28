from typing import Protocol

from app.models.candidate import CandidateContract


class VectorSearchClient(Protocol):
    async def search(self, collection: str, text: str, top_k: int) -> list[dict]: ...


class CandidateRetriever(Protocol):
    async def search(self, text: str, top_k: int) -> list[CandidateContract]: ...

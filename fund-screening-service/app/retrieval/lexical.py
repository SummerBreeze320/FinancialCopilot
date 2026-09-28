from app.models.candidate import CandidateContract


class LexicalEntityRetriever:
    def __init__(self, entities: list[CandidateContract]) -> None:
        self._entities = entities

    async def search(self, text: str, top_k: int) -> list[CandidateContract]:
        ranked = sorted(self._entities, key=lambda item: max((len(alias) for alias in (item.name, *item.aliases) if alias in text), default=0), reverse=True)
        return [item for item in ranked if item.name in text or any(alias in text for alias in item.aliases)][:top_k]

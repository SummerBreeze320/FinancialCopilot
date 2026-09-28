class OpenAiEmbeddingClient:
    def __init__(self, http, model: str) -> None:
        self._http = http
        self._model = model

    async def embed(self, text: str) -> list[float]:
        response = await self._http.post("/embeddings", json={"model": self._model, "input": text})
        response.raise_for_status()
        return response.json()["data"][0]["embedding"]

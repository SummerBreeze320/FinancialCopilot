import asyncio


class OpenAiEmbeddingClient:
    def __init__(self, http, model: str) -> None:
        self._http = http
        self._model = model

    async def embed(self, text: str) -> list[float]:
        response = await self._http.post("/embeddings", json={"model": self._model, "input": text})
        response.raise_for_status()
        return response.json()["data"][0]["embedding"]


class PymilvusVectorSearchClient:
    """把自然语言转向量后执行 Milvus ANN，并保留候选元数据。"""

    def __init__(self, milvus, embedding, collection_aliases: dict[str, str] | None = None) -> None:
        self._milvus = milvus
        self._embedding = embedding
        self._collections = collection_aliases or {}

    async def search(self, collection: str, text: str, top_k: int) -> list[dict]:
        vector = await self._embedding.embed(text)
        collection_name = self._collections.get(collection, collection)
        rows = await asyncio.to_thread(
            self._milvus.search,
            collection_name=collection_name,
            data=[vector],
            limit=top_k,
            output_fields=["*"],
        )
        hits = []
        for hit in rows[0] if rows else []:
            entity = dict(hit.get("entity", {}))
            entity["score"] = float(hit.get("distance", hit.get("score", 0)))
            hits.append(entity)
        return hits

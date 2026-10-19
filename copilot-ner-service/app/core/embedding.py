from __future__ import annotations

import asyncio
import math

import httpx


class EmbeddingError(RuntimeError):
    pass


class EmbeddingDimensionError(EmbeddingError):
    pass


class EmbeddingClient:
    def __init__(
        self,
        http: httpx.AsyncClient,
        model: str,
        dimension: int,
        max_attempts: int = 3,
    ) -> None:
        self._http = http
        self._model = model
        self._dimension = dimension
        self._max_attempts = max_attempts

    async def embed(self, texts: list[str] | tuple[str, ...]) -> list[list[float]]:
        if not texts:
            return []
        response: httpx.Response | None = None
        for attempt in range(self._max_attempts):
            try:
                response = await self._http.post("/embeddings", json={"model": self._model, "input": list(texts)})
                response.raise_for_status()
                break
            except (httpx.TimeoutException, httpx.NetworkError, httpx.HTTPStatusError) as exc:
                retryable = not isinstance(exc, httpx.HTTPStatusError) or exc.response.status_code in {429, 502, 503, 504}
                if not retryable or attempt + 1 == self._max_attempts:
                    raise EmbeddingError(f"embedding request failed: {exc}") from exc
                await asyncio.sleep(0.1 * (2 ** attempt))
        if response is None:
            raise EmbeddingError("embedding request produced no response")
        try:
            items = sorted(response.json()["data"], key=lambda item: item["index"])
        except (KeyError, TypeError, ValueError) as exc:
            raise EmbeddingError("invalid embedding response") from exc
        if len(items) != len(texts):
            raise EmbeddingError("embedding response count mismatch")
        vectors: list[list[float]] = []
        for item in items:
            vector = item.get("embedding")
            if not isinstance(vector, list) or len(vector) != self._dimension:
                raise EmbeddingDimensionError(
                    f"expected {self._dimension} dimensions, got {len(vector) if isinstance(vector, list) else 'invalid'}"
                )
            values = [float(value) for value in vector]
            if not all(math.isfinite(value) for value in values):
                raise EmbeddingDimensionError("embedding contains non-finite values")
            vectors.append(values)
        return vectors

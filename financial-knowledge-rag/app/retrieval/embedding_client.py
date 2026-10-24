"""本地 Ollama / OpenAI 兼容协议 Embedding 向量化客户端。"""

import logging
from typing import List
import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class EmbeddingClient:
    """Embedding 向量化客户端，默认对接本地 Ollama qwen3-embedding 服务。"""

    def __init__(
        self,
        base_url: str | None = None,
        api_key: str | None = None,
        model: str | None = None,
        dim: int | None = None,
        batch_size: int = 16,
        timeout: float = 30.0,
    ) -> None:
        """初始化向量化客户端。

        :param base_url: OpenAI 兼容 API 基础路径，例如 http://127.0.0.1:11434/v1
        :param api_key: 授权 Key
        :param model: 向量模型名称，例如 qwen3-embedding:0.6b
        :param dim: 向量输出维度，例如 1024
        :param batch_size: 单次批量向量化切片数，避免一次性请求显存超载
        :param timeout: 请求超时秒数
        """
        self.base_url = (base_url or settings.embedding_base_url).rstrip("/")
        self.api_key = api_key or settings.embedding_api_key
        self.model = model or settings.embedding_model
        self.dim = dim or settings.embedding_dim
        self.batch_size = batch_size
        self.timeout = timeout

    async def embed_texts(self, texts: List[str]) -> List[List[float]]:
        """批量获取文本的嵌入向量。

        :param texts: 待向量化的纯文本列表
        :return: 向量矩阵，维度为 [len(texts), dim]
        """
        if not texts:
            return []

        all_embeddings: List[List[float]] = []
        url = f"{self.base_url}/embeddings"
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            for i in range(0, len(texts), self.batch_size):
                batch = texts[i : i + self.batch_size]
                payload = {
                    "model": self.model,
                    "input": batch,
                }
                response = await client.post(url, json=payload, headers=headers)
                response.raise_for_status()
                data = response.json().get("data", [])
                # 按照 index 排序确保顺序完全一致
                sorted_data = sorted(
                    data,
                    key=lambda item: item.get("index", 0),
                )
                for item in sorted_data:
                    emb = item["embedding"]
                    all_embeddings.append(emb)

        return all_embeddings

    async def embed_query(self, query: str) -> List[float]:
        """对单条检索 Query 提示词进行向量化。

        :param query: 检索提问文本
        :return: 1024 维度的单向量浮点数组
        """
        vectors = await self.embed_texts([query])
        if vectors:
            return vectors[0]
        return [0.0] * self.dim

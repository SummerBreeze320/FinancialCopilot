"""检索与持久化模块。

包含本地 Embedding 客户端、PostgreSQL pgvector 存储引擎、双路混合检索与神经重排器。
"""

from app.retrieval.embedding_client import EmbeddingClient
from app.retrieval.hybrid_retriever import HybridRetriever
from app.retrieval.pgvector_store import PgVectorStore
from app.retrieval.reranker import NeuralReranker

__all__ = [
    "EmbeddingClient",
    "PgVectorStore",
    "HybridRetriever",
    "NeuralReranker",
]

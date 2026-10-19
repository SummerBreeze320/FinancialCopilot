"""Graphiti 初始化 — 复用项目已有的 Neo4j + DeepSeek + Ollama Embedding"""

import logging
from graphiti_core import Graphiti
from graphiti_core.llm_client.config import LLMConfig
from graphiti_core.llm_client.openai_generic_client import OpenAIGenericClient
from graphiti_core.embedder.openai import OpenAIEmbedder, OpenAIEmbedderConfig
from graphiti_core.cross_encoder.openai_reranker_client import OpenAIRerankerClient

from app.config import settings

logger = logging.getLogger(__name__)

_graphiti_instance: Graphiti | None = None


def get_graphiti() -> Graphiti:
    global _graphiti_instance
    if _graphiti_instance is not None:
        return _graphiti_instance

    cfg = settings

    llm_config = LLMConfig(
        api_key=cfg.llm.api_key,
        model=cfg.llm.model,
        small_model=cfg.llm.small_model,
        base_url=cfg.llm.base_url,
    )
    llm_client = OpenAIGenericClient(config=llm_config)

    embedder = OpenAIEmbedder(
        config=OpenAIEmbedderConfig(
            api_key="ollama",
            embedding_model=cfg.embedding.model,
            embedding_dim=cfg.embedding.dim,
            base_url=cfg.embedding.base_url,
        )
    )

    cross_encoder = OpenAIRerankerClient(
        client=llm_client,
        config=llm_config,
    )

    _graphiti_instance = Graphiti(
        cfg.neo4j.uri,
        cfg.neo4j.user,
        cfg.neo4j.password,
        llm_client=llm_client,
        embedder=embedder,
        cross_encoder=cross_encoder,
    )

    logger.info("Graphiti initialized: Neo4j=%s, LLM=%s, Embedding=%s",
                cfg.neo4j.uri, cfg.llm.model, cfg.embedding.model)
    return _graphiti_instance


async def init_graphiti_indices():
    """启动时调用一次，创建 Neo4j 索引和约束"""
    g = get_graphiti()
    try:
        await g.build_indices_and_constraints()
        logger.info("Graphiti indices and constraints built successfully")
    except Exception as e:
        logger.error("Failed to build Graphiti indices: %s", e)
        raise


async def close_graphiti():
    global _graphiti_instance
    if _graphiti_instance is not None:
        await _graphiti_instance.close()
        _graphiti_instance = None
        logger.info("Graphiti connection closed")

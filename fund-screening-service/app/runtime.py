from pathlib import Path
from typing import Any

import httpx

from app.catalog.loader import Catalog
from app.config import Settings
from app.graph.checkpoint import build_checkpointer
from app.graph.compiler import LangGraphCommandCompiler
from app.graph.nodes import ScreeningGraphNodes
from app.graph.workflow import build_screening_graph
from app.pipeline.analysis import RecallByConditionService
from app.pipeline.builder import CommandBuilder
from app.pipeline.intent_funnel import IntentFunnel
from app.pipeline.compiler import RagCommandCompiler
from app.retrieval.clients import OpenAiEmbeddingClient
from app.retrieval.lexical import LexicalEntityRetriever
from app.retrieval.llm import OpenAiSemanticLlm
from app.retrieval.pgvector import LazyAsyncpgPool, PgVectorMetricRetriever, PgVectorScopeRetriever, PgVectorSearchClient


class UnifiedAnalysisFacade:
    """门面适配器：将 OpenAiSemanticLlm 与 RecallByConditionService 统一对接到 ScreeningGraphNodes。"""

    def __init__(self, llm: OpenAiSemanticLlm, recall_service: RecallByConditionService) -> None:
        self._llm = llm
        self._recall = recall_service

    async def generate_semantic_plan(self, query: str, previous_dsl: dict | None = None):
        return await self._llm.generate_semantic_plan(query, previous_dsl)

    async def generate_edit_plan(self, query: str, previous_summary: dict):
        return await self._llm.generate_edit_plan(query, previous_summary)

    async def recall_by_condition(self, plan: Any, target_condition_ids: list[str] | None = None):
        return await self._recall.recall(plan, target_condition_ids=target_condition_ids)

    async def adjudicate_candidates(self, plan: Any, by_condition: dict[str, Any], route: str = "simple"):
        return await self._llm.adjudicate_candidates(plan, by_condition, route=route)


def build_production_compiler(settings: Settings | None = None) -> LangGraphCommandCompiler:
    settings = settings or Settings()
    if not all((settings.metrics_catalog_path, settings.sectors_catalog_path, settings.entity_catalog_path)):
        raise RuntimeError("metrics, sectors and entity catalog paths are required in rag mode")
    catalog = Catalog.from_files(Path(settings.metrics_catalog_path), Path(settings.sectors_catalog_path), Path(settings.entity_catalog_path))
    llm_http = httpx.AsyncClient(
        base_url=settings.llm_base_url,
        headers={"Authorization": f"Bearer {settings.llm_api_key}"},
        timeout=settings.llm_timeout_seconds,
        trust_env=False,
    )
    embedding_http = httpx.AsyncClient(base_url=settings.embedding_base_url, headers={"Authorization": f"Bearer {settings.embedding_api_key}"}, timeout=30, trust_env=False)
    llm = OpenAiSemanticLlm(
        llm_http,
        settings.llm_model,
        settings.llm_reasoning_effort,
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key,
    )
    embedding = OpenAiEmbeddingClient(embedding_http, settings.embedding_model)
    vector = PgVectorSearchClient(LazyAsyncpgPool(settings.database_url), embedding)

    metric_ret = PgVectorMetricRetriever(vector)
    scope_ret = PgVectorScopeRetriever(vector)
    recall_service = RecallByConditionService(
        metric_retriever=metric_ret,
        scope_retriever=scope_ret,
        entity_retriever=LexicalEntityRetriever(catalog.entities),
    )
    analysis = UnifiedAnalysisFacade(llm, recall_service)

    nodes = ScreeningGraphNodes(
        analysis,
        CommandBuilder(default_sector_id=settings.default_sector_id),
        candidates_catalog=catalog.metrics,
        intent_funnel=IntentFunnel(llm.classify_context_intent, llm.classify_complex_intent),
    )
    checkpointer = build_checkpointer(settings.redis_url)
    graph = build_screening_graph(nodes, checkpointer=checkpointer)
    compiler = LangGraphCommandCompiler(graph)
    compiler.metric_retriever = metric_ret
    compiler.scope_retriever = scope_ret
    return compiler

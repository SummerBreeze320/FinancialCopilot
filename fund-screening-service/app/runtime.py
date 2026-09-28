from pathlib import Path

import httpx

from app.catalog.loader import Catalog
from app.config import Settings
from app.graph.checkpoint import build_checkpointer
from app.graph.compiler import LangGraphCommandCompiler
from app.graph.nodes import ScreeningGraphNodes
from app.graph.workflow import build_screening_graph
from app.pipeline.analysis import AnalysisService
from app.pipeline.builder import CommandBuilder
from app.pipeline.compiler import RagCommandCompiler
from app.retrieval.clients import OpenAiEmbeddingClient
from app.retrieval.lexical import LexicalEntityRetriever
from app.retrieval.llm import OpenAiSemanticLlm
from app.retrieval.pgvector import LazyAsyncpgPool, PgVectorMetricRetriever, PgVectorScopeRetriever, PgVectorSearchClient


def build_production_compiler(settings: Settings | None = None) -> LangGraphCommandCompiler:
    settings = settings or Settings()
    if not all((settings.metrics_catalog_path, settings.sectors_catalog_path, settings.entity_catalog_path)):
        raise RuntimeError("metrics, sectors and entity catalog paths are required in rag mode")
    catalog = Catalog.from_files(Path(settings.metrics_catalog_path), Path(settings.sectors_catalog_path), Path(settings.entity_catalog_path))
    llm_http = httpx.AsyncClient(base_url=settings.llm_base_url, headers={"Authorization": f"Bearer {settings.llm_api_key}"}, timeout=60, trust_env=False)
    embedding_http = httpx.AsyncClient(base_url=settings.embedding_base_url, headers={"Authorization": f"Bearer {settings.embedding_api_key}"}, timeout=30, trust_env=False)
    llm = OpenAiSemanticLlm(llm_http, settings.llm_model, settings.llm_reasoning_effort)
    embedding = OpenAiEmbeddingClient(embedding_http, settings.embedding_model)
    vector = PgVectorSearchClient(LazyAsyncpgPool(settings.database_url), embedding)
    analysis = AnalysisService(
        llm.generate_plan,
        PgVectorMetricRetriever(vector),
        PgVectorScopeRetriever(vector),
        LexicalEntityRetriever(catalog.entities),
        llm.adjudicate,
    )
    nodes = ScreeningGraphNodes(analysis, CommandBuilder(default_sector_id=settings.default_sector_id))
    checkpointer = build_checkpointer(settings.redis_url)
    graph = build_screening_graph(nodes, checkpointer=checkpointer)
    return LangGraphCommandCompiler(graph)

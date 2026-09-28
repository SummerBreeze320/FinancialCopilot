from __future__ import annotations

from typing import Any
from fastapi import FastAPI

from app.api.commands import router as command_router
from app.api.schema import router as schema_router
from app.config import Settings
from app.graph.compiler import LangGraphCommandCompiler
from app.graph.nodes import ScreeningGraphNodes
from app.graph.workflow import build_screening_graph
from app.pipeline.analysis import AnalysisService, RecallPlan
from app.pipeline.builder import CommandBuilder
from app.pipeline.orchestrator import ScreeningOrchestrator
from app.runtime import build_production_compiler


def _build_default_graph():
    class _EmptyRetriever:
        async def search(self, q: str, l: int = 10):
            return []

    analysis = AnalysisService(
        lambda q: RecallPlan(("metric",)),
        _EmptyRetriever(),
        _EmptyRetriever(),
        _EmptyRetriever(),
        lambda q, p, pool: {"groups": [], "limit": 20},
    )
    nodes = ScreeningGraphNodes(analysis, CommandBuilder())
    return build_screening_graph(nodes)


def create_app(compiler: Any = None, graph: Any = None) -> FastAPI:
    """创建基金筛选 HTTP 应用。"""
    app = FastAPI(title="Fund Screening Service", version="0.1.0")

    if graph is None:
        if compiler is not None and hasattr(compiler, "graph"):
            graph = compiler.graph
        else:
            graph = _build_default_graph()

    app.state.graph = graph
    if compiler is None and graph is not None:
        compiler = LangGraphCommandCompiler(graph)

    app.state.orchestrator = ScreeningOrchestrator(compiler=compiler, graph=graph)
    app.include_router(schema_router)
    app.include_router(command_router)

    @app.get("/v1/fund-screening/health")
    async def health() -> dict[str, object]:
        return {
            "service": "fund-screening-service",
            "dependencies": {
                "catalog": "configured",
                "pgvector": "configured",
                "redis": "configured",
                "llm": "configured",
            },
        }

    return app


def create_configured_app() -> FastAPI:
    settings = Settings()
    paths_ready = all((settings.metrics_catalog_path, settings.sectors_catalog_path, settings.entity_catalog_path))
    compiler = build_production_compiler(settings) if settings.pipeline_mode == "rag" and paths_ready else None
    configured = create_app(compiler=compiler)
    configured.state.pipeline_mode = "rag" if compiler else "mvp"
    return configured


app = create_configured_app()

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


from app.catalog.defaults import DEFAULT_ENTITIES, DEFAULT_METRICS, DEFAULT_SCOPES
from app.models.candidate import CandidateContract
from app.models.edit_plan import EditPlan
from app.models.semantic_plan import (
    AdjudicationResult,
    ConditionBinding,
    MetricLeaf,
    MetricTree,
    PlanGroup,
    SemanticPlan,
)
from app.pipeline.analysis import RecallByConditionService


class DefaultCandidateRetriever:
    def __init__(self, candidates: list[CandidateContract]) -> None:
        self._candidates = candidates

    async def search(self, query: str, limit: int = 10) -> list[CandidateContract]:
        results = []
        for c in self._candidates:
            if c.name in query or any(a in query for a in c.aliases) or query in c.name or any(query in a for a in c.aliases):
                results.append(c)
        return results[:limit]


def _build_default_graph(checkpointer: Any = None):
    if checkpointer is None:
        from langgraph.checkpoint.memory import MemorySaver
        checkpointer = MemorySaver()
    analysis = AnalysisService(
        lambda q: RecallPlan(("metric", "scope", "entity")),
        DefaultCandidateRetriever(DEFAULT_METRICS),
        DefaultCandidateRetriever(DEFAULT_SCOPES),
        DefaultCandidateRetriever(DEFAULT_ENTITIES),
        lambda q, p, pool: {"groups": [], "limit": 20},
    )
    nodes = ScreeningGraphNodes(
        analysis=analysis,
        builder=CommandBuilder(),
        candidates_catalog=DEFAULT_METRICS,
    )
    return build_screening_graph(nodes, checkpointer=checkpointer)


def create_app(compiler: Any = None, graph: Any = None) -> FastAPI:
    """创建基金筛选 HTTP 应用。"""
    app = FastAPI(title="Fund Screening Service", version="0.1.0")

    if graph is None:
        if compiler is not None and hasattr(compiler, "graph"):
            graph = compiler.graph
        elif compiler is not None:
            graph = None
        else:
            graph = _build_default_graph()


    app.state.graph = graph
    if compiler is None and graph is not None:
        compiler = LangGraphCommandCompiler(graph)

    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.staticfiles import StaticFiles
    from fastapi.responses import FileResponse
    from pathlib import Path

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.state.compiler = compiler
    app.state.orchestrator = ScreeningOrchestrator(compiler=compiler, graph=graph)
    app.include_router(schema_router)
    app.include_router(command_router)

    static_dir = Path(__file__).resolve().parents[1] / "static"
    static_dir.mkdir(parents=True, exist_ok=True)
    app.mount("/static", StaticFiles(directory=static_dir), name="static")

    @app.get("/")
    async def index() -> Any:
        index_file = static_dir / "index.html"
        if index_file.exists():
            return FileResponse(index_file)
        return {"status": "ok", "message": "Fund Screening API is online"}

    @app.get("/health")
    @app.get("/v1/fund-screening/health")
    async def health() -> dict[str, object]:
        return {
            "status": "ok",
            "service": "fund-screening-service",
            "dependencies": {
                "catalog": "configured",
                "pgvector": "configured",
                "redis": "configured",
                "llm": "configured",
            },
        }

    @app.get("/ready")
    @app.get("/v1/fund-screening/ready")
    async def ready() -> dict[str, object]:
        catalog_status = "ready" if getattr(app.state, "compiler", None) is not None or getattr(app.state, "graph", None) is not None else "degraded"
        llm_status = "ready"
        redis_status = "ready"
        overall = "ready" if catalog_status == "ready" else "degraded"
        return {
            "status": overall,
            "service": "fund-screening-service",
            "dependencies": {
                "catalog": catalog_status,
                "pgvector": "ready",
                "redis": redis_status,
                "llm": llm_status,
            },
        }

    return app


def create_configured_app() -> FastAPI:
    from pathlib import Path
    settings = Settings()
    if not (settings.metrics_catalog_path and settings.sectors_catalog_path and settings.entity_catalog_path):
        service_root = Path(__file__).resolve().parents[1]
        repo_root = service_root
        while repo_root.parent != repo_root:
            if (repo_root / "docs" / "temp" / "metrics.json").exists():
                break
            repo_root = repo_root.parent
        settings.metrics_catalog_path = str(repo_root / "docs" / "temp" / "metrics.json")
        settings.sectors_catalog_path = str(repo_root / "docs" / "temp" / "sectors.json")
        settings.entity_catalog_path = str(service_root / "tests" / "fixtures" / "entities-small.json")

    paths_ready = all((settings.metrics_catalog_path, settings.sectors_catalog_path, settings.entity_catalog_path))
    compiler = build_production_compiler(settings) if settings.pipeline_mode == "rag" and paths_ready else None
    configured = create_app(compiler=compiler)
    configured.state.pipeline_mode = "rag" if compiler else "mvp"
    return configured


app = create_configured_app()
